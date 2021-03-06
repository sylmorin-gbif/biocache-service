/**************************************************************************
 *  Copyright (C) 2012 Atlas of Living Australia
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
package au.org.ala.biocache.service;

import com.fasterxml.jackson.core.type.TypeReference;
import org.ala.client.model.LogEventVO;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestOperations;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Implementation of @see au.org.ala.biocache.service.LoggerService that
 * performs lookup via HTTP GET to webservice. 
 *
 * NC: 20130924 - Instead of caching on request. The cache reloaded based on a schedule
 * 
 * @author Nick dos Remedios (nick.dosremedios@csiro.au)
 */
@Component("loggerRestService")
public class LoggerRestService implements LoggerService {

    private final static Logger logger = Logger.getLogger(LoggerRestService.class);

    private List<Map<String,Object>> loggerReasons = RestartDataService.get(this, "loggerReasons", new TypeReference<ArrayList<Map<String,Object>>>(){}, ArrayList.class);
    private List<Map<String,Object>> loggerSources = RestartDataService.get(this, "loggerSources", new TypeReference<ArrayList<Map<String,Object>>>(){}, ArrayList.class);
    private List<Integer> reasonIds = RestartDataService.get(this, "reasonIds", new TypeReference<ArrayList<Integer>>(){}, ArrayList.class);
    private List<Integer> sourceIds = RestartDataService.get(this, "sourceIds", new TypeReference<ArrayList<Integer>>(){}, ArrayList.class);

    //Used to wait for reloadCache() to complete
    private CountDownLatch initialised = new CountDownLatch(1);

    @Value("${logger.service.url:https://logger.ala.org.au/service/logger}")
    protected String loggerUriPrefix;
    //NC 20131018: Allow cache to be disabled via config (enabled by default)
    @Value("${caches.log.enabled:true}")
    protected Boolean enabled =null;

    @Inject
    private RestOperations restTemplate; // NB MappingJacksonHttpMessageConverter() injected by Spring

    @Override    
    public List<Map<String,Object>> getReasons() {
        isReady();

        return loggerReasons;
    }

    @Override    
    public List<Map<String,Object>> getSources() {
        isReady();

        return loggerSources;
    }
    
    @Override 
    public List<Integer> getReasonIds(){
        isReady();

        return reasonIds; 
    }
    
    @Override
    public List<Integer> getSourceIds(){
        isReady();

        return sourceIds;
    }

    @Override
    public void logEvent(LogEventVO logEvent) {

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, logEvent.getUserAgent());
        HttpEntity<LogEventVO> request = new HttpEntity<>(logEvent, headers);
        try {

            ResponseEntity<Void> response = restTemplate.postForEntity(loggerUriPrefix, request, Void.class);

            if (response.getStatusCode() != HttpStatus.OK) {
                logger.warn("failed to log event");
            }

        } catch (Exception e) {
            logger.warn("failed to log event", e);
        }
    }

    /**
     * x
     * wait for reloadCache()
     */
    private void isReady() {
        try {
            initialised.await();
        } catch (Exception e) {
            logger.error(e);
        }
    }

    /**
     * Use a fixed delay so that the next time it is run depends on the last time it finished
     */
    @Scheduled(fixedDelay = 43200000)// schedule to run every 12 hours
    public void reloadCache() {
        init();
    }

    @PostConstruct
    public void init() {
        if (loggerReasons.size() > 0) {
            //data exists, no need to wait
            initialised.countDown();
        }
        if (enabled) {
            logger.info("Refreshing the log sources and reasons");
            List list;

            list = getEntities(LoggerType.reasons);
            if (list.size() > 0) loggerReasons = list;

            list = getEntities(LoggerType.sources);
            if (list.size() > 0) loggerSources = list;

            //now get the ids
            list = getIdList(loggerReasons);
            if (list.size() > 0) reasonIds = list;

            list = getIdList(loggerSources);
            if (list.size() > 0) sourceIds = list;
        } else {
            if (reasonIds == null) {
                logger.info("Providing some sensible default values for the log cache");
                reasonIds = new ArrayList<Integer>();
                sourceIds = new ArrayList<Integer>();
                //provide sensible defaults for the ID lists
                for (Integer i = 0; i < 11; i++) {
                    reasonIds.add(i);
                    if (i < 8) {
                        sourceIds.add(i);
                    }
                }
            }
        }
        initialised.countDown();
    }

    /**
     * Generates an id list from the supplied list
     * @param list
     * @return
     */
    protected List<Integer> getIdList(List<Map<String,Object>> list){
        List<Integer> returnList = new ArrayList<Integer>();
        for(Map<String,Object>value:list){
            returnList.add((Integer)value.get("id"));
        }
        return returnList;
    }
    
    /**
     * Get a list of entities for the given LoggerType
     *
     * @param type
     * @return
     */
    protected List<Map<String,Object>> getEntities(LoggerType type) {
        List<Map<String,Object>> entities = new ArrayList<Map<String,Object>>();

        try {
            final String jsonUri = loggerUriPrefix + "/" + type.name();
            logger.info("Requesting " + type.name() + " via: " + jsonUri);
            entities = restTemplate.getForObject(jsonUri, List.class);
            logger.info("The values : " + entities);
        } catch (Exception ex) {
            logger.error("RestTemplate error: " + ex.getMessage(), ex);
        }

        return entities;
    }

    /**
     * Enum for logger entity types
     */
    protected enum LoggerType {
        reasons, sources;
    }
}
