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
package com.scooter1556.sms.server.controller;
import com.scooter1556.sms.server.domain.ClientProfile;
import com.scooter1556.sms.server.domain.Job;
import com.scooter1556.sms.server.domain.Session;
import com.scooter1556.sms.server.service.LogService;
import com.scooter1556.sms.server.service.NetworkService;
import com.scooter1556.sms.server.service.SessionService;
import com.scooter1556.sms.server.utilities.NetworkUtils;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value="/session")
public class SessionController {
    private static final String CLASS_NAME = "SessionController";

    @Autowired
    private SessionService sessionService;

    @Autowired
    private NetworkService networkService;

    @ApiOperation(value = "Add a new session")
    @ApiResponses(value = {
        @ApiResponse(code = HttpServletResponse.SC_OK, message = "Session added successfully"),
        @ApiResponse(code = HttpServletResponse.SC_INTERNAL_SERVER_ERROR, message = "Failed to add session"),
        @ApiResponse(code = HttpServletResponse.SC_EXPECTATION_FAILED, message = "Client profile is invalid")
    })
    @CrossOrigin
    @RequestMapping(value="/add", method=RequestMethod.POST)
    public ResponseEntity<String> addSession(
            @ApiParam(value = "Session ID", required = false) @RequestParam(value = "id", required = false) UUID id,
            @ApiParam(value = "Client profile for session", required = false) @RequestBody(required = false) ClientProfile profile,
            HttpServletRequest request)
    {
        // Check the client profile
        if(profile != null) {
            if(profile.getClient() == null || profile.getFormat() == null || profile.getCodecs() == null || profile.getFormats() == null) {
                return new ResponseEntity<>("Client profile invalid.", HttpStatus.EXPECTATION_FAILED);
            }

            // Set client status in profile
            profile.setLocal(isLocal(request));
            profile.setUrl(getURL(request));
        }

        // Add session
        UUID sid  = sessionService.addSession(id, request.getUserPrincipal().getName(), profile);

        if(sid == null) {
            return new ResponseEntity<>("Failed to add session!", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return new ResponseEntity<>(sid.toString(), HttpStatus.OK);
    }

    @ApiOperation(value = "Add client profile for a given session")
    @ApiResponses(value = {
        @ApiResponse(code = HttpServletResponse.SC_OK, message = "Client profile updated successfully"),
        @ApiResponse(code = HttpServletResponse.SC_EXPECTATION_FAILED, message = "Client profile is invalid"),
        @ApiResponse(code = HttpServletResponse.SC_NOT_FOUND, message = "Session does not exist")
    })
    @CrossOrigin
    @RequestMapping(value="/update/{sid}", method=RequestMethod.POST, headers = {"Content-type=application/json"})
    public ResponseEntity<String> updateClientProfile(
            @ApiParam(value = "Session ID", required = true) @PathVariable("sid") UUID sid,
            @ApiParam(value = "Updated client profile", required = true) @RequestBody ClientProfile profile,
            HttpServletRequest request)
    {
        // Check the client profile
        if(profile == null || profile.getClient() == null || profile.getCodecs() == null || profile.getFormats() == null || profile.getFormat() == null) {
            LogService.getInstance().addLogEntry(LogService.Level.WARN, CLASS_NAME, "Failed to update client profile for session " + sid + ", profile is invalid.", null);
            return new ResponseEntity<>("Client profile invalid.", HttpStatus.EXPECTATION_FAILED);
        }

        // Retrieve session
        Session session = sessionService.getSessionById(sid);

        // Check session is valid
        if (session == null) {
            LogService.getInstance().addLogEntry(LogService.Level.WARN, CLASS_NAME, "Session does not exist with ID: " + sid, null);
            return new ResponseEntity<>("Session does not exist with ID: " + sid, HttpStatus.NOT_FOUND);
        }

        // Set client status in profile
        profile.setLocal(isLocal(request));
        profile.setUrl(getURL(request));

        // Update client profile
        session.setClientProfile(profile);

        LogService.getInstance().addLogEntry(LogService.Level.INFO, CLASS_NAME, "Session updated: " + session.toString(), null);
        return new ResponseEntity<>("Client profile updated successfully.", HttpStatus.OK);
    }

    @ApiOperation(value = "End session")
    @ApiResponses(value = {
        @ApiResponse(code = HttpServletResponse.SC_OK, message = "Session ended successfully"),
        @ApiResponse(code = HttpServletResponse.SC_NO_CONTENT, message = "Session does not exist")
    })
    @CrossOrigin
    @RequestMapping(value="/end/{sid}", method=RequestMethod.DELETE)
    public ResponseEntity<String> endSession(
            @ApiParam(value = "Session ID", required = true) @PathVariable("sid") UUID sid)
    {
        // Check session is valid
        if (!sessionService.isSessionAvailable(sid)) {
            LogService.getInstance().addLogEntry(LogService.Level.WARN, CLASS_NAME, "Session does not exist with ID: " + sid, null);
            return new ResponseEntity<>("Session does not exist with ID: " + sid, HttpStatus.NO_CONTENT);
        }

        // Remove session
        sessionService.removeSessions(sid);

        LogService.getInstance().addLogEntry(LogService.Level.INFO, CLASS_NAME, "Ended session with ID: " + sid, null);
        return new ResponseEntity<>("Ended session with ID: " + sid, HttpStatus.OK);
    }

    @ApiOperation(value = "End job")
    @ApiResponses(value = {
        @ApiResponse(code = HttpServletResponse.SC_OK, message = "Job(s) ended successfully"),
        @ApiResponse(code = HttpServletResponse.SC_NO_CONTENT, message = "Session or job does not exist")
    })
    @CrossOrigin
    @RequestMapping(value="/end/{sid}/{meid}", method=RequestMethod.DELETE)
    public ResponseEntity<String> endJob(
            @ApiParam(value = "Session ID", required = true) @PathVariable("sid") UUID sid,
            @ApiParam(value = "Media element ID (or 'all' to end all jobs for session)", required = true) @PathVariable("meid") String meid)
    {
        Session session = sessionService.getSessionById(sid);

        // Check session is valid
        if (session == null) {
            LogService.getInstance().addLogEntry(LogService.Level.WARN, CLASS_NAME, "Session does not exist with ID: " + sid, null);
            return new ResponseEntity<>("Session does not exist with ID: " + sid, HttpStatus.NO_CONTENT);
        }

        // Check for special case where all jobs for a given session should be ended
        if(meid.equalsIgnoreCase("all")) {
            sessionService.endJobs(session, null);

            LogService.getInstance().addLogEntry(LogService.Level.DEBUG, CLASS_NAME, "Ended all jobs for session with ID: " + sid, null);
            return new ResponseEntity<>("Ended all jobs for session with ID: " + sid, HttpStatus.OK);
        }

        Job job = session.getJobByMediaElementId(UUID.fromString(meid));

        // Check job exists
        if (job == null) {
            LogService.getInstance().addLogEntry(LogService.Level.WARN, CLASS_NAME, "Job does not exist for media element with ID: " + meid, null);
            return new ResponseEntity<>("Job does not exist for media element with ID: " + meid, HttpStatus.NO_CONTENT);
        }

        LogService.getInstance().addLogEntry(LogService.Level.WARN, CLASS_NAME, session.getUsername() + " finished streaming '" + job.getMediaElement().getTitle() + "'.", null);

        // End job
        sessionService.endJobs(session, UUID.fromString(meid));

        LogService.getInstance().addLogEntry(LogService.Level.DEBUG, CLASS_NAME, "Ended job with ID: " + job.getId(), null);
        return new ResponseEntity<>("Ended job with ID: " + job.getId(), HttpStatus.OK);
    }

    @ApiOperation(value = "Suspend job")
    @ApiResponses(value = {
        @ApiResponse(code = HttpServletResponse.SC_OK, message = "Job suspended successfully"),
        @ApiResponse(code = HttpServletResponse.SC_NO_CONTENT, message = "Session or job does not exist")
    })
    @CrossOrigin
    @RequestMapping(value="/suspend/{sid}/{meid}", method=RequestMethod.PUT)
    public ResponseEntity<String> suspendJob(
            @ApiParam(value = "Session ID", required = true) @PathVariable("sid") UUID sid,
            @ApiParam(value = "Media element ID", required = true) @PathVariable("meid") String meid)
    {
        Session session = sessionService.getSessionById(sid);

        // Check session is valid
        if (session == null) {
            LogService.getInstance().addLogEntry(LogService.Level.WARN, CLASS_NAME, "Session does not exist with ID: " + sid, null);
            return new ResponseEntity<>("Session does not exist with ID: " + sid, HttpStatus.NO_CONTENT);
        }

        // Check job exists
        Job job = session.getJobByMediaElementId(UUID.fromString(meid));

        if (job == null) {
            LogService.getInstance().addLogEntry(LogService.Level.WARN, CLASS_NAME, "Job does not exist for media element with ID: " + meid, null);
            return new ResponseEntity<>("Job does not exist for media element with ID: " + meid, HttpStatus.NO_CONTENT);
        }

        LogService.getInstance().addLogEntry(LogService.Level.WARN, CLASS_NAME, session.getUsername() + " finished streaming '" + job.getMediaElement().getTitle() + "'.", null);

        // Suspend job
        sessionService.suspendJobs(session, UUID.fromString(meid));

        LogService.getInstance().addLogEntry(LogService.Level.DEBUG, CLASS_NAME, "Suspended job with ID: " + job.getId(), null);
        return new ResponseEntity<>("Suspended job with ID: " + job.getId(), HttpStatus.OK);
    }

    //
    // Helper Functions
    //
    private boolean isLocal(HttpServletRequest request) {
        // Determine if the device is on the local network
        boolean isLocal = false;

        try {
            InetAddress local = InetAddress.getByName(request.getLocalAddr());
            InetAddress remote = InetAddress.getByName(request.getRemoteAddr());

            // Handle reverse-proxy
            if(request.getHeaderNames() != null) {
                for (Enumeration<String> headers = request.getHeaderNames(); headers.hasMoreElements();) {
                    String name = headers.nextElement();
                    if(name.equalsIgnoreCase("X-Forwarded-For")) {
                        String addresses = request.getHeader(name);

                        if(addresses != null) {
                            String ip = addresses.split(",")[0];
                            remote = InetAddress.getByName(ip);
                        }
                    }
                }
            }

            NetworkInterface networkInterface = NetworkInterface.getByInetAddress(local);

            LogService.getInstance().addLogEntry(LogService.Level.INFO, CLASS_NAME, "Client connected with IP " + remote.toString(), null);

            // Check if the remote device is on the same subnet as the server
            for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
                if(address.getAddress().equals(local)) {
                    int mask = address.getNetworkPrefixLength();
                    isLocal = NetworkUtils.isLocalIP(local, remote, mask);
                }
            }

            // Check if request came from public IP if subnet check was false
            if(!isLocal) {
                String ip = networkService.getPublicIP();

                if(ip != null) {
                    isLocal = remote.toString().contains(ip);
                }
            }
        } catch (SocketException | UnknownHostException ex) {
            LogService.getInstance().addLogEntry(LogService.Level.WARN, CLASS_NAME, "Failed to check IP adress of client.", ex);
            return false;
        }

        return isLocal;
    }

    public static String getURL(HttpServletRequest request) {
        if(request == null) {
            return null;
        }

        // Handle reverse-proxy
        if(request.getHeaderNames() != null) {
            String protocol = null;
            String host = null;

            for (Enumeration<String> headers = request.getHeaderNames(); headers.hasMoreElements();) {
                String name = headers.nextElement();

                // Check for host
                if(name.equalsIgnoreCase("X-Forwarded-Host")) {
                    host = request.getHeader(name);
                }

                // Check for protocol
                if(name.equalsIgnoreCase("X-Forwarded-Proto")) {
                    protocol = request.getHeader(name);
                }
            }

            if(protocol != null && host != null) {
                // Build URL
                String url = protocol + "://" + host;
                return url;
            }
        }

        return request.getRequestURL().toString().replaceFirst("/session(.*)", "");
    }
}