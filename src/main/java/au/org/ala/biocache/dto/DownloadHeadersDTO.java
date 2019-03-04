/**************************************************************************
 *  Copyright (C) 2013 Atlas of Living Australia
 *  All Rights Reserved.
 *
 *  The contents of this file are subject to the Mozilla Public
 *  License Version 1.1 (the "License"); you may not use this file
 *  except in compliance with the License. You may obtain a copy of
 *  the License at http://www.mozilla.org/MPL/
 *
 *  Software distributed under the License is distributed on an "AS
 *  IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 *  implied. See the License for the specific language governing
 *  rights and limitations under the License.
 ***************************************************************************/
package au.org.ala.biocache.dto;

/**
 * Download header components
 */
public class DownloadHeadersDTO {

    private String[] sensitiveFields;
    private String[] nonSensitiveFields;
    private String[] formattedHeader;
    private String[] qaFields;
    private String[] analysisFields;
    private String[] speciesListFields;
    private String[] fields;

    public String[] getSensitiveFields() {
        return sensitiveFields;
    }

    public void setSensitiveFields(String[] sensitiveFields) {
        this.sensitiveFields = sensitiveFields;
    }

    public String[] getNonSensitiveFields() {
        return nonSensitiveFields;
    }

    public void setNonSensitiveFields(String[] nonSensitiveFields) {
        this.nonSensitiveFields = nonSensitiveFields;
    }

    public String[] getFormattedHeader() {
        return formattedHeader;
    }

    public void setFormattedHeader(String[] formattedHeader) {
        this.formattedHeader = formattedHeader;
    }

    public String[] getQaFields() {
        return qaFields;
    }

    public void setQaFields(String[] qaFields) {
        this.qaFields = qaFields;
    }

    public String[] getAnalysisFields() {
        return analysisFields;
    }

    public void setAnalysisFields(String[] analysisFields) {
        this.analysisFields = analysisFields;
    }

    public String[] getSpeciesListFields() {
        return speciesListFields;
    }

    public void setSpeciesListFields(String[] speciesListFields) {
        this.speciesListFields = speciesListFields;
    }

    public String[] getFields() {
        return fields;
    }

    public void setFields(String[] fields) {
        this.fields = fields;
    }
}
