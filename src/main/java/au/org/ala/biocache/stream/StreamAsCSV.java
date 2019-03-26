package au.org.ala.biocache.stream;

import au.com.bytecode.opencsv.CSVWriter;
import au.org.ala.biocache.dto.SpatialSearchRequestParams;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.io.Tuple;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class StreamAsCSV implements ProcessInterface {

    private final static Logger logger = Logger.getLogger(StreamAsCSV.class);

    CSVWriter csvWriter;
    SpatialSearchRequestParams requestParams;

    String[] row;

    byte[] bComma;
    byte[] bNewLine;
    byte[] bDblQuote;

    int count = 0;

    //header field identification
    List<String> header = new ArrayList<String>();

    public StreamAsCSV(OutputStream stream, SpatialSearchRequestParams requestParams) {
        this.csvWriter = new CSVWriter(new OutputStreamWriter(stream));
        this.requestParams = requestParams;

        try {
            bComma = ",".getBytes("UTF-8");
            bNewLine = "\n".getBytes("UTF-8");
            bDblQuote = "\"".getBytes("UTF-8");
        } catch (Exception e) {
            logger.error(e);
        }
    }


    public boolean process(Tuple tuple) {
        //write header when writing the first record
        if (count == 0) {
            if (StringUtils.isNotEmpty(requestParams.getFl())) {
                header = Arrays.asList(requestParams.getFl().split(","));
            } else {
                header = new ArrayList<>(tuple.getMap().keySet());
            }
            csvWriter.writeNext(header.toArray(new String[0]));
            row = new String[header.size()];
        }

        count++;

        //write record
        for (int j = 0; j < header.size(); j++) {
            row[j] = format(tuple.get(header.get(j)));
        }

        csvWriter.writeNext(row);

        return true;
    }

    String format(Object item) {
        if (item == null) return "";

        String formatted = null;
        if (item instanceof List) {
            if (requestParams.getIncludeMultivalues()) {
                formatted = StringUtils.join((List) item, '|');
            } else if (((List) item).size() > 0) {
                formatted = String.valueOf(((List) item).get(0));
            }
        } else {
            formatted = String.valueOf(item);
        }
        if (StringUtils.isEmpty(formatted)) {
            return "";
        } else {
            return formatted;
        }
    }

    public boolean flush() {
        try {
            csvWriter.flush();
        } catch (IOException e) {
            logger.error(e);
        }

        return true;
    }
}