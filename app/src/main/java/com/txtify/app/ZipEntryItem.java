package com.txtify.app;

import java.io.Serializable;

// Using Serializable to easily pass this object between activities
public class ZipEntryItem implements Serializable {

    private String fullPath;
    private String fileName;
    private String subfolder;
    private boolean isIncluded;

    public ZipEntryItem(String fullPath, String fileName, String subfolder) {
        this.fullPath = fullPath;
        this.fileName = fileName;
        this.subfolder = subfolder;
        this.isIncluded = true; // All files are included by default
    }

    public String getFullPath() {
        return fullPath;
    }

    public String getFileName() {
        return fileName;
    }

    public String getSubfolder() {
        return subfolder;
    }

    public boolean isIncluded() {
        return isIncluded;
    }

    public void setIncluded(boolean included) {
        isIncluded = included;
    }
}

