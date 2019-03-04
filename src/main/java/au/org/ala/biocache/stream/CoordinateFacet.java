package au.org.ala.biocache.stream;

import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.io.Tuple;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class CoordinateFacet implements ProcessInterface {

    OutputStream out;

    public CoordinateFacet(OutputStream out) {
        this.out = out;
    }

    @Override
    public boolean process(Tuple t) {
        for (Object value : t.getMap().values()) {
            if (value instanceof String && StringUtils.isNotEmpty((String) value)) {
                try {
                    out.write(((String) value).getBytes(StandardCharsets.UTF_8));
                    out.write("\n".getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        return true;
    }

    @Override
    public boolean flush() {
        return true;
    }
}
