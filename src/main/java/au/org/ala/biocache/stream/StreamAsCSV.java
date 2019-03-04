package au.org.ala.biocache.stream;

import au.org.ala.biocache.dto.SpatialSearchRequestParams;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.io.Tuple;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.TreeSet;

public class StreamAsCSV implements ProcessInterface {

    private final static Logger logger = Logger.getLogger(StreamAsCSV.class);

    OutputStream stream;
    SpatialSearchRequestParams requestParams;

    byte[] bComma;
    byte[] bNewLine;
    byte[] bDblQuote;

    int count = 0;

    public StreamAsCSV(OutputStream stream, SpatialSearchRequestParams requestParams) {
        this.stream = stream;
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
        try {
            if (tuple != null && tuple.fieldNames.size() > 0) {

                //header field identification
                ArrayList<String> header = new ArrayList<String>();

                //requestParams.getFl() is never empty
                if (requestParams.getFl() == null || requestParams.getFl().isEmpty()) {
                    TreeSet<String> unique = new TreeSet<String>();
                    unique.addAll(tuple.fieldNames);

                    header = new ArrayList<>(unique);
                }

                //write header when writing the first record
                if (count == 0) {
                    for (int i = 0; i < header.size(); i++) {
                        if (i > 0) {
                            stream.write(bComma);
                        }
                        stream.write(header.get(i).getBytes("UTF-8"));
                    }
                }

                //write record
                stream.write(bNewLine);
                for (int j = 0; j < header.size(); j++) {
                    if (j > 0) {
                        stream.write(bComma);
                    }
                    Object value = tuple.get(header.get(j));
                    if (value != null && StringUtils.isNotEmpty(value.toString())) {
                        stream.write(bDblQuote);
                        stream.write(String.valueOf(value).replace("\"", "\"\"").getBytes("UTF-8"));
                        stream.write(bDblQuote);
                    }
                }
            }

            return true;
        } catch (IOException e) {
            logger.warn("ProcessSearchTuple terminated: " + e.getMessage());
            return false;
        }
    }

    public boolean flush() {
        return true;
    }
}