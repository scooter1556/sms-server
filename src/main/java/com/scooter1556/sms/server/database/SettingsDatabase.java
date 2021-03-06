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
package com.scooter1556.sms.server.database;

import com.scooter1556.sms.server.domain.MediaFolder;
import com.scooter1556.sms.server.exception.DatabaseException;
import com.scooter1556.sms.server.service.LogService;
import com.scooter1556.sms.server.service.LogService.Level;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public final class SettingsDatabase extends Database {
    private static final String CLASS_NAME = "SettingsDatabase";
    
    public static final String DB_NAME = "Settings";
    public static final int DB_VERSION = 2;
    
    public SettingsDatabase() {
        super(DB_NAME, DB_VERSION);   
        
        // Initialise database
        try {
            LogService.getInstance().addLogEntry(Level.INFO, CLASS_NAME, "Initialising database.", null);
            super.initialise();
        } catch (DatabaseException ex) {
            LogService.getInstance().addLogEntry(LogService.Level.ERROR, CLASS_NAME, "Error initialising database.", ex);
        } 
    }
    
    public static final class MediaFolderMapper implements RowMapper {
        @Override
        public MediaFolder mapRow(ResultSet rs, int rowNum) throws SQLException {
            MediaFolder mediaFolder = new MediaFolder();
            mediaFolder.setID(UUID.fromString(rs.getString("ID")));
            mediaFolder.setName(rs.getString("Name"));
            mediaFolder.setType(rs.getByte("Type"));
            mediaFolder.setPath(rs.getString("Path"));
            mediaFolder.setFolders(rs.getLong("Folders"));
            mediaFolder.setFiles(rs.getLong("Files"));
            mediaFolder.setLastScanned(rs.getTimestamp("LastScanned"));
            mediaFolder.setEnabled(rs.getBoolean("Enabled"));
            return mediaFolder;
        }
    }
    
    @Override
    public void create() {
        LogService.getInstance().addLogEntry(Level.INFO, CLASS_NAME, "Creating database.", null);
        
        try {
            // Media Folders
            getJdbcTemplate().execute("CREATE TABLE IF NOT EXISTS MediaFolder ("
                    + "ID UUID NOT NULL,"
                    + "Name VARCHAR(50) NOT NULL,"
                    + "Type TINYINT,"
                    + "Path VARCHAR NOT NULL,"
                    + "Folders BIGINT,"
                    + "Files BIGINT,"
                    + "LastScanned TIMESTAMP,"
                    + "Enabled BOOLEAN DEFAULT 1 NOT NULL,"
                    + "PRIMARY KEY (ID))");
            
        } catch (DataAccessException x) {
            LogService.getInstance().addLogEntry(Level.ERROR, CLASS_NAME, "Error creating database.", x);
        }
    }
    
    @Override
    public void upgrade(int oldVersion, int newVersion) {
        LogService.getInstance().addLogEntry(LogService.Level.INFO, CLASS_NAME, "Upgrading database from version " + oldVersion + " to " + newVersion, null);
    
        if(newVersion == 2) {
            getJdbcTemplate().execute("DROP TABLE IF EXISTS MediaFolder");
            create();
        }
    }
    
    @Override
    public void downgrade(int oldVersion, int newVersion) {
        LogService.getInstance().addLogEntry(LogService.Level.INFO, CLASS_NAME, "Downgrading database from version " + oldVersion + " to " + newVersion, null);
        
        // Delete table and re-create
        getJdbcTemplate().execute("DROP TABLE IF EXISTS MediaFolder");
        create();
    }
}

