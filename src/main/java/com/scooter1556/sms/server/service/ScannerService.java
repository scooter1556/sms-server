/*
 * Author: Scott Ware <scoot.software@gmail.com>
 * Copyright (c) 2015 Scott Ware
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.scooter1556.sms.server.service;

import com.scooter1556.sms.server.dao.JobDao;
import com.scooter1556.sms.server.dao.MediaDao;
import com.scooter1556.sms.server.dao.SettingsDao;
import com.scooter1556.sms.server.domain.Job;
import com.scooter1556.sms.server.domain.MediaElement;
import com.scooter1556.sms.server.domain.MediaElement.AudioStream;
import com.scooter1556.sms.server.domain.MediaElement.DirectoryMediaType;
import com.scooter1556.sms.server.domain.MediaElement.MediaElementType;
import com.scooter1556.sms.server.domain.MediaElement.SubtitleStream;
import com.scooter1556.sms.server.domain.MediaElement.VideoStream;
import com.scooter1556.sms.server.domain.MediaFolder;
import com.scooter1556.sms.server.domain.Playlist;
import com.scooter1556.sms.server.service.LogService.Level;
import com.scooter1556.sms.server.service.parser.FrameParser;
import com.scooter1556.sms.server.service.parser.MetadataParser;
import com.scooter1556.sms.server.service.parser.NFOParser;
import com.scooter1556.sms.server.service.parser.NFOParser.NFOData;
import com.scooter1556.sms.server.utilities.LogUtils;
import com.scooter1556.sms.server.utilities.MediaUtils;
import com.scooter1556.sms.server.utilities.PlaylistUtils;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.FileVisitResult.SKIP_SIBLINGS;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Timestamp;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import org.springframework.stereotype.Service;

@Service
@EnableScheduling
public class ScannerService implements DisposableBean {

    private static final String CLASS_NAME = "MediaScannerService";

    @Autowired
    private SettingsDao settingsDao;

    @Autowired
    private MediaDao mediaDao;

    @Autowired
    private JobDao jobDao;
    
    @Autowired
    private MetadataParser metadataParser;

    @Autowired
    private NFOParser nfoParser;
    
    @Autowired
    private FrameParser frameParser;
    
    @Autowired
    private PlaylistService playlistService;

    private static final String[] INFO_FILE_TYPES = {"nfo"};
    private static final String EXCLUDED_FILE_NAMES = "Extras,extras";

    private static final Pattern FILE_NAME = Pattern.compile("(.+)(\\s+[(\\[](\\d{4})[)\\]])$?");

    private long mTotal = 0, dTotal = 0;
        
    // Media scanning thread pool
    ExecutorService scanningThreads = null;
    
    // Deep scan executor
    ExecutorService deepScanExecutor = null;
    
    // Logs
    String deepScanLog;
    
    // End scanning jobs on application exit
    @Override
    public void destroy() {
        if(isScanning()) {
            scanningThreads.shutdownNow();
        }
        
        stopDeepScan();
    }
    
    // Check for inactive jobs at midnight
    @Scheduled(cron=SettingsService.DEFAULT_DEEPSCAN_SCHEDULE)
    private void deepScan() {
        // Check a scanning process is not already active
        if (isScanning() || isDeepScanning()) {
            return;
        }
        
        // Check there are not currently jobs active
        List<Job> jobs = jobDao.getActiveJobs();
        
        if (jobs != null) {
            for(Job job : jobs) {
                if(job.getType() == Job.JobType.VIDEO_STREAM) {
                    return;
                }
            }
        }
        
        // List of streams to scan
        List<VideoStream> streams = mediaDao.getIncompleteVideoStreams();
        
        // Start scanning
        if(!streams.isEmpty()) {
            // Start scanning playlists
            startDeepScan(streams);
        }
        
        LogService.getInstance().addLogEntry(LogService.Level.INFO, CLASS_NAME, "Started scheduled deep scan of media.", null);
    }

    //
    // Returns whether media folders are currently being scanned.
    //
    public synchronized boolean isScanning() {
        // Check if we have any scanning threads
        if(scanningThreads == null) {
            return false;
        }
        
        // Check if scanning threads have terminated
        return !scanningThreads.isTerminated();
    }
    
    //
    // Returns whether deep scan is in progress.
    //
    public synchronized boolean isDeepScanning() {
        if(deepScanExecutor == null) {
            return false;
        }
        
        return !deepScanExecutor.isTerminated();
    }

    //
    // Returns the number of files scanned so far.
    //
    public long getScanCount() {
        return mTotal;
    }
    
    //
    // Returns the number of streams scanned so far.
    //
    public long getDeepScanCount() {
        return dTotal;
    }

    //
    // Scans media in a separate thread.
    //
    public synchronized void startMediaScanning(List<MediaFolder> folders) {
        // Check if media is already being scanned
        if (isScanning()) {
            return;
        }
        
        // Stop deep scanning if in progress
        stopDeepScan();
        
        // Reset scan count
        mTotal = 0;
        
        // Create media scanning threads
        scanningThreads = Executors.newFixedThreadPool(folders.size());

        // Submit scanning jobs for each media folder
        for (final MediaFolder folder : folders) {
            scanningThreads.submit(new Runnable() {
                @Override
                public void run() {
                    scanMediaFolder(folder);
                }
            });
        }

        // Shutdown thread pool so no further threads can be added
        scanningThreads.shutdown();
    }
    
    //
    // Scans playlist in a separate thread.
    //
    public synchronized void startPlaylistScanning(List<Playlist> playlists) {
        // Check if media is already being scanned
        if (isScanning()) {
            return;
        }
        
        // Create media scanning threads
        scanningThreads = Executors.newFixedThreadPool(playlists.size());

        // Submit processing jobs for each playlist
        for (final Playlist playlist : playlists) {
            scanningThreads.submit(new Runnable() {
                @Override
                public void run() {
                    scanPlaylist(playlist);
                }
            });
        }

        // Shutdown thread pool so no further threads can be added
        scanningThreads.shutdown();
    }
    
    //
    // Performs a deep scan of media streams
    //
    public synchronized void startDeepScan(final List<VideoStream> streams) {
        // Check if media is already being scanned
        if (isScanning() || isDeepScanning()) {
            return;
        }
        
        // Create log file
        Timestamp scanTime = new Timestamp(new Date().getTime());
        deepScanLog = SettingsService.getInstance().getLogDirectory() + "/deepscan-" + scanTime + ".log";
        
        // Reset counter
        dTotal = 0;
        
        // Create media scanning threads
        deepScanExecutor = Executors.newSingleThreadExecutor();

        deepScanExecutor.submit(new Runnable() {
            @Override
            public void run() {
                LogUtils.writeToLog(deepScanLog, "Found " + streams.size() + " streams to parse.", Level.DEBUG);
                
                for(VideoStream stream : streams) {
                    dTotal ++;
                    
                    LogUtils.writeToLog(deepScanLog, "Scanning stream " + stream.getStreamId() + " for media element with id " + stream.getMediaElementId(), Level.DEBUG);

                    VideoStream update = frameParser.parse(stream);
                    
                    if(update != null) {
                        mediaDao.updateVideoStream(update);
                        LogUtils.writeToLog(deepScanLog, stream.toString(), Level.DEBUG);
                    }
                    
                    LogUtils.writeToLog(deepScanLog, "Finished Scanning stream " + stream.getStreamId() + " for media element with id " + stream.getMediaElementId(), Level.DEBUG);
                }
            }
        });
        
        deepScanExecutor.shutdownNow();
    }
    
    public void stopDeepScan() {
        if(deepScanExecutor != null && !deepScanExecutor.isTerminated()) {
            deepScanExecutor.shutdownNow();
            frameParser.stop();
            
            LogUtils.writeToLog(deepScanLog, "Deep scan terminated early!", Level.DEBUG);
        }
    }
    
    private void scanPlaylist(Playlist playlist) {
        // Check this is a file based playlist
        if(playlist.getPath() == null || playlist.getPath().isEmpty()) {
            return;
        }
        
        LogService.getInstance().addLogEntry(LogService.Level.INFO, CLASS_NAME, "Scanning playlist " + playlist.getPath(), null);
        
        // Remove existing playlist content
        mediaDao.removePlaylistContent(playlist.getID());

        // Parse playlist
        List<MediaElement> mediaElements = playlistService.parsePlaylist(playlist.getPath());

        if(mediaElements == null || mediaElements.isEmpty()) {
            LogService.getInstance().addLogEntry(LogService.Level.INFO, CLASS_NAME, "No content found for playlist " + playlist.getPath(), null);
            return;
        }

        // Update playlist content
        mediaDao.setPlaylistContent(playlist.getID(), mediaElements);
        
        LogService.getInstance().addLogEntry(LogService.Level.INFO, CLASS_NAME, "Finished scanning playlist " + playlist.getPath() + " (Found " + mediaElements.size() + " items)", null);
    }
    
    private void scanMediaFolder(MediaFolder folder) {
        Path path = FileSystems.getDefault().getPath(folder.getPath());
        ParseFiles fileParser = new ParseFiles(folder);

        try {
            // Start Scan directory
            LogService.getInstance().addLogEntry(LogService.Level.INFO, CLASS_NAME, "Scanning media folder " + folder.getPath(), null);
            Files.walkFileTree(path, fileParser);

            // Add new media elements in database
            if(!fileParser.getNewMediaElements().isEmpty()) {
                mediaDao.createMediaElements(fileParser.getNewMediaElements());
            }

            // Update existing media elements in database
            if(!fileParser.getUpdatedMediaElements().isEmpty()) {
                mediaDao.updateMediaElementsByID(fileParser.getUpdatedMediaElements());
            }
            
            // Extract media streams from parsed media elements
            List<VideoStream> vStreams = new ArrayList<>();
            List<AudioStream> aStreams = new ArrayList<>();
            List<SubtitleStream> sStreams = new ArrayList<>();
            
            for(MediaElement element : fileParser.getAllMediaElements()) {
                if(element.getVideoStreams() != null) {
                    vStreams.addAll(element.getVideoStreams());
                }
                
                if(element.getAudioStreams() != null) {
                    aStreams.addAll(element.getAudioStreams());
                }
                
                if(element.getSubtitleStreams() != null) {
                    sStreams.addAll(element.getSubtitleStreams());
                }
            }
            
            // Add media streams to database
            mediaDao.createVideoStreams(vStreams);
            mediaDao.createAudioStreams(aStreams);
            mediaDao.createSubtitleStreams(sStreams);
            
            // Add new playlists
            if(!fileParser.getNewPlaylists().isEmpty()) {
                for(Playlist playlist : fileParser.getNewPlaylists()) {
                    mediaDao.createPlaylist(playlist);
                }
            }
            
            // Update existing playlists
            if(!fileParser.getUpdatedPlaylists().isEmpty()) {                
                for(Playlist playlist : fileParser.getUpdatedPlaylists()) {
                    mediaDao.updatePlaylistLastScanned(playlist.getID(), fileParser.getScanTime());
                }
            }
            
            // Remove files which no longer exist
            mediaDao.removeDeletedMediaElements(folder.getPath(), fileParser.getScanTime());
            mediaDao.removeDeletedPlaylists(folder.getPath(), fileParser.getScanTime());
            
            // Update folder statistics
            folder.setFolders(fileParser.getFolders());
            folder.setFiles(fileParser.getFiles());
            folder.setLastScanned(fileParser.getScanTime());
            
            // Determine primary media type in folder
            if(folder.getType() == null || folder.getType() == MediaFolder.ContentType.UNKNOWN) {
                int audio = 0, video = 0, playlist;
                
                // Get number of playlists
                playlist = fileParser.getAllPlaylists().size();
                
                // Iterate over media elements to determine number of each type
                for(MediaElement element : fileParser.getAllMediaElements()) {
                    switch(element.getType()) {
                        case MediaElementType.AUDIO:
                            audio++;
                            break;
                            
                        case MediaElementType.VIDEO:
                            video++;
                            break;
                    }
                }
                
                if(audio == 0 && video == 0 && playlist > 0) {
                    folder.setType(MediaFolder.ContentType.PLAYLIST);
                } else if(audio > video) {
                    folder.setType(MediaFolder.ContentType.AUDIO);
                } else if(video > audio) {
                    folder.setType(MediaFolder.ContentType.VIDEO);
                }
            }
            
            settingsDao.updateMediaFolder(folder);

            LogService.getInstance().addLogEntry(LogService.Level.INFO, CLASS_NAME, "Finished scanning media folder " + folder.getPath() + " (Items Scanned: " + fileParser.getTotal() + ", Folders: " + fileParser.getFolders() + ", Files: " + fileParser.getFiles() + ", Playlists: " + fileParser.getPlaylists() + ")", null);
        } catch (IOException ex) {
            LogService.getInstance().addLogEntry(LogService.Level.ERROR, CLASS_NAME, "Error scanning media folder " + folder.getPath(), ex);
        }       
    }

    private class ParseFiles extends SimpleFileVisitor<Path> {
        Timestamp scanTime = new Timestamp(new Date().getTime());
        private final MediaFolder folder;
        private final Deque<MediaElement> directories = new ArrayDeque<>();
        private final Deque<Deque<MediaElement>> directoryElements = new ArrayDeque<>();
        private final Deque<NFOData> nfoData = new ArrayDeque<>();
        private final HashSet<Path> directoriesToUpdate = new HashSet<>();
        String log = SettingsService.getInstance().getLogDirectory() + "/mediascanner-" + scanTime + ".log";
        boolean directoryChanged = false;

        
        private final List<MediaElement> newElements = new ArrayList<>();
        private final List<MediaElement> updatedElements = new ArrayList<>();
        private final List<Playlist> newPlaylists = new ArrayList<>();
        private final List<Playlist> updatedPlaylists = new ArrayList<>();
        
        private long files = 0, folders = 0, playlists = 0;
        
        public ParseFiles(MediaFolder folder) {
            this.folder = folder;
            folders = 0;
            files = 0;
            playlists = 0;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attr) {            
            // Check if we need to scan this directory
            if(!MediaUtils.containsMedia(dir.toFile(), true) && !PlaylistUtils.containsPlaylists(dir.toFile())) {
                return SKIP_SIBLINGS;
            }
            
            LogUtils.writeToLog(log, "Parsing directory " + dir.toString(), Level.DEBUG);
            
            // Initialise variables
            directoryChanged = false;
            directoryElements.add(new ArrayDeque<MediaElement>());
            
            // Determine if this directory has changed
            directoryChanged = folder.getLastScanned() == null || new Timestamp(attr.lastModifiedTime().toMillis()).after(folder.getLastScanned());
            
            // If this is the root directory procede without processing
            if(dir.toString().equals(folder.getPath())) {
                return CONTINUE;
            }
                
            // Check if directory already has an associated media element
            MediaElement directory = mediaDao.getMediaElementByPath(dir.toString());

            if (directory == null) {
                directory = getMediaElementFromPath(dir);
                directory.setType(MediaElementType.DIRECTORY);
            }

            if(directoryChanged || directory.getLastScanned().equals(scanTime)) {
                // Add directory to update list
                directoriesToUpdate.add(dir);
                
                // Parse file name for media element attributes
                directory = parseFileName(dir, directory);
                
                // Determine if the directory should be excluded from categorised lists
                if (isExcluded(dir.getFileName())) {
                    directory.setExcluded(true);
                }
            }
            
            // Add directory to list
            directories.add(directory);

            return CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attr) {
            // Determine type of file and how to process it
            if(MediaUtils.isMediaFile(file)) {
                LogUtils.writeToLog(log, "Parsing file " + file.toString(), Level.DEBUG);
                
                // Update statistics
                mTotal++;
                files++;
                
                // Check if media file already has an associated media element
                MediaElement mediaElement = mediaDao.getMediaElementByPath(file.toString());

                if (mediaElement == null) {
                    mediaElement = getMediaElementFromPath(file);
                    mediaElement.setFormat(MediaUtils.getFileExtension(file.getFileName()));
                }
                
                // Determine if we need to process the file
                if(folder.getLastScanned() == null || new Timestamp(attr.lastModifiedTime().toMillis()).after(folder.getLastScanned()) || mediaElement.getLastScanned().equals(scanTime)) {
                    LogUtils.writeToLog(log, "Processing file " + file.toString(), Level.DEBUG);
                    
                    // Add parent directory to update list
                    directoriesToUpdate.add(file.getParent());
                    
                    // Parse file name for media element attributes
                    mediaElement = parseFileName(file.getFileName(), mediaElement);
                    mediaElement.setSize(attr.size());

                    // Remove existing media streams and parse Metadata
                    mediaDao.removeStreamsByMediaElementId(mediaElement.getID());
                    metadataParser.parse(mediaElement);
                }
                
                // Add media element to list
                directoryElements.peekLast().add(mediaElement);
                
            } else if(PlaylistUtils.isPlaylist(file)) {
                LogUtils.writeToLog(log, "Parsing playlist " + file.toString(), Level.DEBUG);
                
                // Update statistics
                mTotal++;
                files++;
                playlists++;
                
                // Check if playlist already has an associated database entry
                Playlist playlist = mediaDao.getPlaylistByPath(file.toString());

                // Generate new playlist object or update existing one if necessary
                if (playlist == null) {
                    playlist = getPlaylistFromPath(file);
                    newPlaylists.add(playlist);
                } else {
                    if(folder.getLastScanned() == null || new Timestamp(attr.lastModifiedTime().toMillis()).after(folder.getLastScanned())) {
                        LogUtils.writeToLog(log, "Processing playlist " + file.toString(), Level.DEBUG);
                        playlist.setLastScanned(null);
                    }
                    
                    // Add to list of playlists to update
                    updatedPlaylists.add(playlist);
                }
            } else if(isInfoFile(file)) {
                // Determine if we need to parse this file
                if(directoryChanged || folder.getLastScanned() == null || new Timestamp(attr.lastModifiedTime().toMillis()).after(folder.getLastScanned())) {
                    LogUtils.writeToLog(log, "Processing file " + file.toString(), Level.DEBUG);
                    nfoData.add(nfoParser.parse(file));
                }
            }
            
            return CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            // Update statistics
            mTotal++;
            folders++;
            
            MediaElement directory = null;
            Deque<MediaElement> dirElements;
            Deque<NFOData> dirData = new ArrayDeque<>();
            
            // Retrieve directory from list
            if(!directories.isEmpty()) {
                directory = directories.removeLast();
            }
            
            // Get child elements for directory
            dirElements = directoryElements.removeLast();
            
            // Get NFO data for directory
            if(!nfoData.isEmpty()) {
                while(nfoData.peekLast() != null && nfoData.peekLast().getPath().getParent().equals(dir)) {
                    dirData.add(nfoData.removeLast());
                }
            }
            
            // Process child media elements
            for(MediaElement element : dirElements) {
                if(!dirData.isEmpty()) {
                    boolean dataFound = false;
                    
                    // Test for file specific data
                    for(NFOData test : dirData) {
                        if(test.getPath().getFileName().toString().contains(element.getTitle())) {
                            LogUtils.writeToLog(log, "Parsing NFO file " + test.getPath(), Level.DEBUG);
                            nfoParser.updateMediaElement(element, test);
                            dirData.remove(test);
                            dataFound = true;
                            break;  
                        }
                    }
                    
                    // Use generic data for directory
                    if(!dataFound) {
                        NFOData data = dirData.getFirst();
                        LogUtils.writeToLog(log, "Parsing NFO file " + data.getPath(), Level.DEBUG);
                        nfoParser.updateMediaElement(element, data);
                    }
                }
                
                // Set media elements to add or update
                if(element.getLastScanned().equals(scanTime)) {
                    newElements.add(element);
                } else {
                    element.setLastScanned(scanTime);
                    updatedElements.add(element);
                }
                
                LogUtils.writeToLog(log, element.toString(), Level.INSANE);
            }
            
            // Update directory element if necessary
            if(directory != null && directoriesToUpdate.contains(dir)) {
                LogUtils.writeToLog(log, "Processing directory " + dir.toString(), Level.DEBUG);
                
                if(!dirData.isEmpty()) {
                    nfoParser.updateMediaElement(directory, dirData.removeFirst());
                }
                
                // Determine directory media type
                directory.setDirectoryType(getDirectoryMediaType(dirElements));
                
                // Check for common attributes if the directory contains media
                if (!directory.getDirectoryType().equals(DirectoryMediaType.NONE)) {
                    // Get year if not set
                    if (directory.getYear() == 0) {
                        directory.setYear(getDirectoryYear(dirElements));
                    }

                    // Get common media attributes for the directory if available (artist, collection, TV series etc...)                
                    if (directory.getDirectoryType().equals(DirectoryMediaType.AUDIO) || directory.getDirectoryType().equals(DirectoryMediaType.MIXED)) {
                        // Get directory description if possible.
                        String description = getDirectoryDescription(dirElements);

                        if (description != null) {
                            directory.setDescription(description);
                        }

                        // Get directory artist if possible.
                        String artist = getDirectoryArtist(dirElements);

                        // Try album artist
                        if (artist == null) {
                            artist = getDirectoryAlbumArtist(dirElements);
                        }

                        // Try root directory name
                        if (artist == null) {
                            artist = getDirectoryRoot(dir, folder.getPath());
                        }

                        // Set directory artist if found
                        if (artist != null) {
                            directory.setArtist(artist);
                        }
                    }

                    if (directory.getDirectoryType().equals(DirectoryMediaType.VIDEO)) {
                        // Get directory collection/series if possible.
                        String collection = getDirectoryCollection(dirElements);
                        
                        // Try root directory name
                        if (collection == null) {
                            collection = getDirectoryRoot(dir, folder.getPath());
                        }

                        // Set directory collection if found
                        if (collection != null) {
                            directory.setCollection(collection);
                        }
                    }
                } else {
                    // Exclude directories from categorised lists which do not directly contain media
                    directory.setExcluded(true);
                }
                
                LogUtils.writeToLog(log, directory.toString(), Level.INSANE);
            }
            
            // Set media elements to add or update
            if(directory != null) {
                if(directory.getLastScanned().equals(scanTime)) {
                    newElements.add(directory);
                } else {
                    directory.setLastScanned(scanTime);
                    updatedElements.add(directory);
                }                
            }
            
            LogUtils.writeToLog(log, "Finished parsing directory " + dir.toString(), Level.DEBUG);
            
            return CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            LogService.getInstance().addLogEntry(LogService.Level.ERROR, CLASS_NAME, "Error parsing file " + file.toString(), exc);
            return CONTINUE;
        }
        
        //
        // Helper Functions
        //

        private boolean isInfoFile(Path path) {
            for (String type : INFO_FILE_TYPES) {
                if (path.getFileName().toString().toLowerCase().endsWith("." + type)) {
                    return true;
                }
            }

            return false;
        }

        private boolean isExcluded(Path path) {
            for (String name : EXCLUDED_FILE_NAMES.split(",")) {
                if (path.getFileName().toString().equals(name)) {
                    return true;
                }
            }

            return false;
        }

        // Return a new media element object for a given file
        private MediaElement getMediaElementFromPath(Path path) {
            MediaElement mediaElement = new MediaElement();

            // Set ID
            mediaElement.setID(UUID.randomUUID());
            
            // Set common attributes
            mediaElement.setPath(path.toString());
            mediaElement.setParentPath(path.getParent().toString());
            mediaElement.setLastScanned(scanTime);

            return mediaElement;
        }
        
        // Return a new playlist object for a given file
        private Playlist getPlaylistFromPath(Path path) {
            Playlist playlist = new Playlist();

            // Set ID
            playlist.setID(UUID.randomUUID());
            
            // Set common attributes
            playlist.setName(FilenameUtils.getBaseName(path.toString()));
            playlist.setPath(path.toString());
            playlist.setParentPath(path.getParent().toString());
            playlist.setLastScanned(scanTime);

            return playlist;
        }

        // Get title and other information from file name
        private MediaElement parseFileName(Path path, MediaElement mediaElement) {
            // Parse file name for title and year
            Matcher matcher = FILE_NAME.matcher(path.getFileName().toString());

            if (matcher.find()) {
                mediaElement.setTitle(String.valueOf(matcher.group(1)));

                if (matcher.group(2) != null) {
                    mediaElement.setYear(Short.parseShort(matcher.group(3)));
                }
            } else if(path.toFile().isDirectory()){
                mediaElement.setTitle(path.getFileName().toString());
            } else {
                int extensionIndex = path.getFileName().toString().lastIndexOf(".");
                mediaElement.setTitle(extensionIndex == -1 ? path.getFileName().toString() : path.getFileName().toString().substring(0, extensionIndex));
            }

            return mediaElement;
        }

        private Byte getDirectoryMediaType(Deque<MediaElement> mediaElements) {
            Byte type = DirectoryMediaType.NONE;

            for (MediaElement child : mediaElements) {
                if (child.getType() == MediaElementType.AUDIO || child.getType() == MediaElementType.VIDEO) {
                    // Set an initial media type
                    if (type == DirectoryMediaType.NONE) {
                        type = child.getType();
                    } else if (child.getType().compareTo(type) != 0) {
                        return DirectoryMediaType.MIXED;
                    }
                }
            }

            return type;
        }

        // Get directory year from child media elements
        private Short getDirectoryYear(Deque<MediaElement> mediaElements) {
            Short year = 0;

            for (MediaElement child : mediaElements) {
                if (child.getType() != MediaElementType.DIRECTORY) {
                    if (child.getYear() > 0) {
                        // Set an initial year
                        if (year == 0) {
                            year = child.getYear();
                        } else if (child.getYear().intValue() != year.intValue()) {
                            return 0;
                        }
                    }
                }
            }

            return year;
        }

        // Get directory artist from child media elements
        private String getDirectoryArtist(Deque<MediaElement> mediaElements) {
            String artist = null;

            for (MediaElement child : mediaElements) {
                if (child.getType() == MediaElementType.AUDIO) {
                    if (child.getArtist() != null) {
                        // Set an initial artist
                        if (artist == null) {
                            artist = child.getArtist();
                        } else if (!child.getArtist().equals(artist)) {
                            return null;
                        }
                    }
                }
            }

            return artist;
        }

        // Get directory album artist from child media elements
        private String getDirectoryAlbumArtist(Deque<MediaElement> mediaElements) {
            String albumArtist = null;

            for (MediaElement child : mediaElements) {
                if (child.getType() == MediaElementType.AUDIO) {
                    if (child.getAlbumArtist() != null) {
                        // Set an initial album artist
                        if (albumArtist == null) {
                            albumArtist = child.getAlbumArtist();
                        } else if (!child.getAlbumArtist().equals(albumArtist)) {
                            return null;
                        }
                    }
                }
            }

            return albumArtist;
        }

        // Get directory collection from child media elements
        private String getDirectoryCollection(Deque<MediaElement> mediaElements) {
            String collection = null;
            
            for (MediaElement child : mediaElements) {
                if (child.getType() == MediaElementType.VIDEO) {
                    if (child.getCollection() != null) {
                        // Set an initial collection
                        if (collection == null) {
                            collection = child.getCollection();
                        } else if (!child.getCollection().equals(collection)) {
                            return null;
                        }
                    }
                }
            }

            return collection;
        }

        // Get directory description from child media elements (audio only)
        private String getDirectoryDescription(Deque<MediaElement> mediaElements) {
            String description = null;

            for (MediaElement child : mediaElements) {
                if (child.getType() == MediaElementType.AUDIO) {
                    if (child.getDescription() != null) {
                        // Set an initial description
                        if (description == null) {
                            description = child.getDescription();
                        } else if (!child.getDescription().equals(description)) {
                            return null;
                        }
                    }
                }
            }

            return description;
        }

        // Depending on directory structure this could return artist, series or collection based on the parent directory name.
        private String getDirectoryRoot(Path path, String mediaFolderPath) {
            File dir = path.toFile();
            
            // Check variables
            if (!dir.isDirectory()) {
                return null;
            }

            // If the parent directory is the current media folder forget it
            if (dir.getParent().equals(mediaFolderPath)) {
                return null;
            }

            // Check if the root directory contains media, if so forget it
            if (MediaUtils.containsMedia(dir.getParentFile(), false)) {
                return null;
            }
            
            return dir.getParentFile().getName();
        }
        
        public long getTotal() {
            return files + folders + playlists;
        }

        public long getPlaylists() {
            return playlists;
        }
        
        public long getFiles() {
            return files;
        }

        public long getFolders() {
            return folders;
        }

        public Timestamp getScanTime() {
            return scanTime;
        }
        
        public List<MediaElement> getNewMediaElements() {
            return newElements;
        }
        
        public List<MediaElement> getUpdatedMediaElements() {
            return updatedElements;
        }
        
        public List<MediaElement> getAllMediaElements() {
            List<MediaElement> all = new ArrayList<>();
            
            all.addAll(newElements);
            all.addAll(updatedElements);
            
            return all;
        }
        
        public List<Playlist> getNewPlaylists() {
            return newPlaylists;
        }
        
        public List<Playlist> getUpdatedPlaylists() {
            return updatedPlaylists;
        }
        
        public List<Playlist> getAllPlaylists() {
            List<Playlist> all = new ArrayList<>();
            
            all.addAll(newPlaylists);
            all.addAll(updatedPlaylists);
            
            return all;
        }
    }
}