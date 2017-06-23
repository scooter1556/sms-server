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
package com.scooter1556.sms.server.utilities;

import java.io.File;
import java.nio.file.Path;

public class MediaUtils {
    
    public static String getFileExtension(Path path) {
        int extensionIndex = path.getFileName().toString().lastIndexOf(".");
        return extensionIndex == -1 ? null : path.getFileName().toString().substring(extensionIndex + 1).toLowerCase().trim();
    }
    
    public static boolean isMediaFile(Path path) {
        for (String type : TranscodeUtils.SUPPORTED_FILE_EXTENSIONS) {
            if (path.getFileName().toString().toLowerCase().endsWith("." + type)) {
                return true;
            }
        }

        return false;
    }
    
    // Determines if a directory contains media
    public static boolean containsMedia(File directory, boolean includeDir) {
        for (File file : directory.listFiles()) {
            if(!file.isHidden()) {
                if(includeDir && file.isDirectory()) {
                    return true;
                }

                if(isMediaFile(file.toPath())) {
                    return true;
                }
            }                    
        }

        return false;
    }
    
}
