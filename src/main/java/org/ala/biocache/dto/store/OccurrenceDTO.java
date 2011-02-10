/*
 *  Copyright (C) 2011 Atlas of Living Australia
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
 */

package org.ala.biocache.dto.store;

import au.org.ala.biocache.FullRecord;
import au.org.ala.biocache.QualityAssertion;
import java.io.Serializable;
import java.util.List;

/**
 * Stores the Occurrence information about a specific occurrence
 * from the biocache store
 *
 * @author "Natasha Carter <natasha.carter@csiro.au>"
 */
public class OccurrenceDTO implements Serializable{

    private FullRecord raw;
    private FullRecord processed;
    private FullRecord consensus;
    private List<QualityAssertion> systemAssertions;
    private List<QualityAssertion> userAssertions;

    public OccurrenceDTO() {
    }
    public OccurrenceDTO(FullRecord[] record){
        if(record.length==3){
            raw = record[0];
            processed = record[1];
            consensus = record[2];
        }
    }

    public FullRecord getConsensus() {
        return consensus;
    }

    public void setConsensus(FullRecord consensus) {
        this.consensus = consensus;
    }

    public FullRecord getProcessed() {
        return processed;
    }

    public void setProcessed(FullRecord processed) {
        this.processed = processed;
    }

    public FullRecord getRaw() {
        return raw;
    }

    public void setRaw(FullRecord raw) {
        this.raw = raw;
    }

    public List<QualityAssertion> getSystemAssertions() {
        return systemAssertions;
    }

    public void setSystemAssertions(List<QualityAssertion> systemAssertions) {
        this.systemAssertions = systemAssertions;
    }

    public List<QualityAssertion> getUserAssertions() {
        return userAssertions;
    }

    public void setUserAssertions(List<QualityAssertion> userAssertions) {
        this.userAssertions = userAssertions;
    }
    

}
