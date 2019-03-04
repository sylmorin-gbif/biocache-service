package au.org.ala.biocache.stream;

import au.com.bytecode.opencsv.CSVReader;
import au.org.ala.biocache.RecordWriter;
import au.org.ala.biocache.dto.DownloadDetailsDTO;
import au.org.ala.biocache.dto.DownloadHeadersDTO;
import au.org.ala.biocache.dto.Kvp;
import au.org.ala.biocache.service.DownloadService;
import au.org.ala.biocache.service.ListsService;
import au.org.ala.biocache.util.LayersStore;
import au.org.ala.biocache.util.SearchUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.io.Tuple;

import java.io.IOException;
import java.io.Reader;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ProcessDownload implements ProcessInterface {

    protected static final Logger logger = Logger.getLogger(ProcessDownload.class);

    final static int MAX_BATCH_SIZE = 1000;

    ConcurrentMap<String, AtomicInteger> uidStats;
    RecordWriter recordWriter;
    DownloadDetailsDTO downloadDetails;
    boolean checkLimit;
    AtomicInteger resultsCount;
    long maxDownloadSize;
    List<String> miscFields;
    DownloadHeadersDTO headers;
    boolean sensitiveDataAllowed;
    ListsService listsService;

    // remote analysis layer intersections require batching for performance reasons
    List<String[]> batch = new ArrayList();
    double[][] points = new double[MAX_BATCH_SIZE][2];

    public ProcessDownload(ConcurrentMap<String, AtomicInteger> uidStats, DownloadHeadersDTO headers,
                           RecordWriter recordWriter, DownloadDetailsDTO downloadDetails, boolean checkLimit,
                           AtomicInteger resultsCount, long maxDownloadSize,
                           List<String> miscFields, boolean sensitiveDataAllowed, ListsService listsService) {
        this.uidStats = uidStats;
        this.headers = headers;
        this.recordWriter = recordWriter;
        this.downloadDetails = downloadDetails;
        this.checkLimit = checkLimit;
        this.resultsCount = resultsCount;
        this.maxDownloadSize = maxDownloadSize;
        this.miscFields = miscFields;
        this.sensitiveDataAllowed = sensitiveDataAllowed;
        this.listsService = listsService;
    }

    /**
     * flush() will finish writing any rows that may be held over in the batch
     *
     * @return
     */
    public boolean flush() {
        // do analysis layer intersections before writing the batch
        intersectAnalysisLayers();

        downloadDetails.updateCounts(batch.size());

        batch.forEach(row -> recordWriter.write(row));
        batch.clear();

        return true;
    }

    /**
     * process() transforms a tuple from /export query() into a single row.
     *
     * @param tuple tuple to be formatted
     * @return
     */
    public boolean process(Tuple tuple) {

        boolean finished = false;

        String[] values = null;

        String[] fields = headers.getFields();
        String[] qaFields = headers.getQaFields();
        String[] analysisLayers = headers.getAnalysisFields();
        String[] speciesListFields = headers.getSpeciesListFields();

        if (tuple.get("data_resource_uid") != null && (!checkLimit || (checkLimit && resultsCount.intValue() < maxDownloadSize))) {

            resultsCount.incrementAndGet();

            // create a column with the correct length
            values = new String[fields.length + analysisLayers.length + speciesListFields.length + qaFields.length];

            //get all the "single" values from the index
            for (int j = 0; j < fields.length; j++) {
                Object obj = tuple.get(fields[j]);
                if (obj == null) {
                    values[j] = "";
                } else if (obj instanceof Collection) {
                    Iterator it = ((Collection) obj).iterator();
                    while (it.hasNext()) {
                        Object value = it.next();
                        if (values[j] != null && values[j].length() > 0) values[j] += "|"; //multivalue separator
                        values[j] = SearchUtils.formatValue(value);

                        //allow requests to include multiple values when requested
                        if (downloadDetails == null || downloadDetails.getRequestParams() == null ||
                                downloadDetails.getRequestParams().getIncludeMultivalues() == null
                                || !downloadDetails.getRequestParams().getIncludeMultivalues()) {
                            break;
                        }
                    }
                } else {
                    values[j] = SearchUtils.formatValue(obj);
                }
            }

            // add species list fields
            if (speciesListFields.length > 0) {
                appendSpeciesListColumns(tuple, values);
            }

            //now handle the assertions
            java.util.Collection<String> assertions = tuple.getStrings("assertions");

            //Handle the case where there a no assertions against a record
            if (assertions == null) {
                assertions = Collections.EMPTY_LIST;
            }

            for (int k = 0; k < qaFields.length; k++) {
                values[fields.length + analysisLayers.length + speciesListFields.length + k] = Boolean.toString(assertions.contains(qaFields[k]));
            }

            // Append previous and new non-empty misc fields.
            // Do not include misc fields if this is a sensitive record and sensitive data is not permitted.
            // This check should not be needed because sensitive record misc data should not be in SOLR.
            if (downloadDetails != null && downloadDetails.getRequestParams() != null && downloadDetails.getRequestParams().getIncludeMisc() &&
                    (sensitiveDataAllowed || "Not sensitive".equals(SearchUtils.formatValue(tuple.getString("sensitive"))) ||
                            "".equals(SearchUtils.formatValue(tuple.getString("sensitive"))))) {
                values = appendMiscColumns(tuple, values);
            }

            // record longitude and latitude for remote analysis layer intersections
            if (analysisLayers.length > 0) {
                recordCoordinates(tuple);
            }

            //increment the counters....
            DownloadService.incrementCount(uidStats, tuple.get("institution_uid"));
            DownloadService.incrementCount(uidStats, tuple.get("collection_uid"));
            DownloadService.incrementCount(uidStats, tuple.get("data_provider_uid"));
            DownloadService.incrementCount(uidStats, tuple.get("data_resource_uid"));

            batch.add(values);

            if (batch.size() > MAX_BATCH_SIZE) {
                flush();
            }
        } else {
            // reached the record limit
            finished = true;
        }

        return finished;
    }

    private void appendSpeciesListColumns(Tuple tuple, String[] values) {
        String[] fields = headers.getFields();
        String[] analysisLayers = headers.getAnalysisFields();
        String[] speciesListFields = headers.getSpeciesListFields();

        String lftString = String.valueOf(tuple.getString("lft"));
        String rgtString = String.valueOf(tuple.getString("rgt"));
        if (StringUtils.isNumeric(lftString)) {
            long lft = Long.parseLong(lftString);
            long rgt = Long.parseLong(rgtString);
            Kvp lftrgt = new Kvp(lft, rgt);

            String drDot = ".";
            String dr = "";
            int fieldIdx = 0;
            for (int i = 0; i < speciesListFields.length; i++) {
                if (speciesListFields[i].startsWith(drDot)) {
                    fieldIdx++;
                } else {
                    dr = speciesListFields[i].split("\\.", 2)[0];
                    drDot = dr + ".";
                    fieldIdx = 0;
                }

                values[analysisLayers.length + fields.length + i] = listsService.getKvpValue(fieldIdx, listsService.getKvp(dr), lftrgt);
            }
        }
    }

    private void intersectAnalysisLayers() {
        String[] analysisLayers = headers.getAnalysisFields();
        String layersServiceUrl = downloadDetails.getRequestParams().getLayersServiceUrl();

        if (batch.size() > 0 && StringUtils.isNotEmpty(layersServiceUrl) && analysisLayers.length > 0) {
            String[] fields = headers.getFields();
            List<String[]> intersection = new ArrayList<String[]>();
            try {
                // only do intersection where there is at least one valid coordinate
                int i = 0;
                for (i = 0; i < batch.size(); i++) {
                    if (points[i][0] != Integer.MIN_VALUE) {
                        break;
                    }
                }
                if (i < batch.size()) {
                    LayersStore ls = new LayersStore(layersServiceUrl);
                    Reader reader = ls.sample(analysisLayers, points, null);

                    CSVReader csv = new CSVReader(reader);
                    intersection = csv.readAll();
                    csv.close();

                    for (int j = 0; j < batch.size(); j++) {
                        if (j > batch.size()) {
                            //+1 offset for header row in intersection list
                            String[] sampling = intersection.get(j + 1);
                            //+2 offset for latitude,longitude columns in sampling array
                            if (sampling != null && sampling.length == analysisLayers.length + 2) {
                                // suitable space is already available in each batch row String[]
                                System.arraycopy(sampling, 2, batch.get(j), fields.length, sampling.length - 2);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                logger.error("Failed to intersect analysis layers", e);
            }
        }
    }

    private void recordCoordinates(Tuple tuple) {
        try {
            Object lon = null;
            Object lat = null;
            if ((lon = tuple.get("sensitive_longitude")) == null || (lat = tuple.get("sensitive_latitude")) == null) {
                lon = tuple.get("longitude");
                lat = tuple.get("latitude");
            }
            if (lon == null || lat == null) {
                // set as invalid longitude
                points[batch.size()][0] = Integer.MIN_VALUE;
                points[batch.size()][1] = Integer.MIN_VALUE;
            } else {
                points[batch.size()][0] = (Double) lon;
                points[batch.size()][1] = (Double) lat;
            }
        } catch (Exception e) {
            // set the coordinates of the point to something that is invalid
            points[batch.size()][0] = Integer.MIN_VALUE;
            points[batch.size()][1] = Integer.MIN_VALUE;
        }
    }

    /**
     * Appending misc columns can change the size of 'values' when new columns are added.
     *
     * @param tuple
     * @param values
     * @return
     */
    private String[] appendMiscColumns(Tuple tuple, String[] values) {
        // append miscValues for columns found
        List<String> miscValues = new ArrayList<String>(miscFields.size());  // TODO: reuse

        // maintain miscFields order using synchronized
        synchronized (miscFields) {
            // append known miscField values
            for (String f : miscFields) {
                miscValues.add(SearchUtils.formatValue(tuple.getString(f)));
                //clear field to avoid repeating the value when looking for new miscValues
                tuple.setMaps(f, null);
            }
            // find and append new miscFields and their values
            for (String key : tuple.fieldNames) {
                if (key != null && key.startsWith("_")) {
                    String value = SearchUtils.formatValue(tuple.get(key));
                    if (StringUtils.isNotEmpty(value)) {
                        miscValues.add(value);
                        miscFields.add(key);
                    }
                }
            }
        }

        // append miscValues to values
        if (miscValues.size() > 0) {
            String[] newValues = new String[miscValues.size() + values.length];
            System.arraycopy(values, 0, newValues, 0, values.length);
            for (int i = 0; i < miscValues.size(); i++) {
                newValues[values.length + i] = miscValues.get(i);
            }
            values = newValues;
        }

        return values;
    }
}
