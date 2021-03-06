package com.scooter1556.sms.server.utilities;

import com.scooter1556.sms.server.service.LogService;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class LogUtils {
    
    private static final String CLASS_NAME = "LogUtils";
    
    public static void writeToLog(String path, String line, byte level, boolean console) {
        if(LogService.getInstance().getLogLevel() < level) {
            return;
        }
        
        // Output to console
        if(console) {
            System.out.println(line);
        }
        
        try {
            PrintWriter out;
            out = new PrintWriter(new BufferedWriter(new FileWriter(path, true)));
            out.println(line);
            out.close();
        } catch (IOException ex) {
            LogService.getInstance().addLogEntry(LogService.Level.ERROR, CLASS_NAME, "Unable to add entry to log file: " + path, ex);
        }
    }
}
