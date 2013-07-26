package com.socrata.api;

import com.socrata.exceptions.LongRunningQueryException;
import com.socrata.exceptions.SodaError;
import com.socrata.model.GeocodingResults;
import com.socrata.model.importer.Dataset;
import com.socrata.model.importer.DatasetInfo;
import com.socrata.model.requests.SodaRequest;
import com.sun.jersey.api.client.ClientResponse;
import org.codehaus.jackson.map.ObjectMapper;

import javax.ws.rs.core.UriBuilder;
import java.net.URI;

/**
 * Implements the methods important to using the Soda workflow.  At a high level,
 * these are the APIs around publishing and determining if processes like geocoding
 * are still going on.
 *
 * To find out more about the publishing cycle, look at http://dev.socrata.com/publishers/workflow
 *
 * However, for frequent updates or large datasets, it is often better to us the Soda2Producer API if you can, because
 * publishing operations are expensive.
 */
public class SodaWorkflow
{
    protected static final String API_BASE_PATH     = "api";
    protected static final String GEO_BASE_PATH     = "geocoding";
    protected static final long   TICKET_CHECK      = 10000L;
    protected static final String VIEWS_BASE_PATH   = "views";

    protected final URI           geocodingUri;
    protected final HttpLowLevel  httpLowLevel;
    protected final ObjectMapper  mapper;
    protected final URI           viewUri;

    /**
     * If long running request is set, a request has already been submitted to the server.
     * Network errors between now and completion do not stop the request from being processed by the server.
     * You may call checkLongRunningRequestStatus to get the result of the long running request.
     */
    protected LongRunningRequest lastLongRunningRequest;

    /**
     * Create a new SodaWorkflow object, using the supplied credentials for authentication.
     *
     * @param url the base URL for the SODA2 domain to access.
     * @param userName user name to log in as
     * @param password password to log in with
     * @param token the App Token to use for authorization and usage tracking.  If this is {@code null}, no value will be sent.
     *
     * @return fully configured SodaWorkflow
     */
    public static final SodaWorkflow newWorkflow(final String url, String userName, String password, String token)
    {
        return new SodaWorkflow(HttpLowLevel.instantiateBasic(url, userName, password, token));
    }


    /**
     * Constructor.
     *
     * @param httpLowLevel the HttpLowLevel this uses to contact the server
     */
    public SodaWorkflow(HttpLowLevel httpLowLevel)
    {
        this.httpLowLevel = httpLowLevel;

        geocodingUri = httpLowLevel.uriBuilder()
                                   .path(API_BASE_PATH)
                                   .path(GEO_BASE_PATH)
                                   .build();

        viewUri = httpLowLevel.uriBuilder()
                              .path(API_BASE_PATH)
                              .path(VIEWS_BASE_PATH)
                              .build();

        mapper = new ObjectMapper();
    }

    /**
     * Gets the underlying connection.
     *
     * @return the underlying connection
     */
    public HttpLowLevel getHttpLowLevel()
    {
        return httpLowLevel;
    }


    /**
     * Publishes a dataset.
     *
     * @param datasetId id of the dataset to publish.
     * @return the view of the published dataset.
     *
     * @throws com.socrata.exceptions.SodaError
     * @throws InterruptedException
     * @throws com.socrata.exceptions.LongRunningQueryException
     */
    public DatasetInfo publish(final String datasetId) throws SodaError, InterruptedException
    {


        waitForPendingGeocoding(datasetId);

        SodaRequest requester = new SodaRequest<String>(datasetId,null)
        {
            public ClientResponse issueRequest() throws LongRunningQueryException, SodaError
            {
                final URI publicationUri = UriBuilder.fromUri(viewUri)
                                                     .path(resourceId)
                                                     .path("publication")
                                                     .build();


                return httpLowLevel.postRaw(publicationUri, HttpLowLevel.JSON_TYPE, "viewId=" + resourceId);
            }
        };

        try {
            ClientResponse response = requester.issueRequest();
            return response.getEntity(DatasetInfo.class);
        } catch (LongRunningQueryException e) {
            setLastLongRunningRequest(new LongRunningRequest(e, DatasetInfo.class, requester));
            return checkLongRunningRequestStatus();
        }
    }

    /**
     * Creates a working copy for the specified dataset ID.  This copy can have any schema changes
     * made to it, as well as replace/append operations.
     *
     * The resulting dataset will not be available to other users until it is published.
     *
     * @param datasetId the id of the dataset.
     * @return reference to the new working copy of this dataset
     * @throws SodaError
     * @throws InterruptedException
     */
    public DatasetInfo createWorkingCopy(final String datasetId) throws SodaError, InterruptedException{

        SodaRequest requester = new SodaRequest<String>(datasetId,null)
        {
            public ClientResponse issueRequest() throws LongRunningQueryException, SodaError
            {
                final URI publicationUri = UriBuilder.fromUri(viewUri)
                                                     .path(resourceId)
                                                     .path("publication.json")
                                                     .queryParam("method", "copy")
                                                     .build();

                return httpLowLevel.postRaw(publicationUri, HttpLowLevel.JSON_TYPE, "method=copy");
            }
        };

        try {
            ClientResponse response = requester.issueRequest();
            return response.getEntity(Dataset.class);
        } catch (LongRunningQueryException e) {
            setLastLongRunningRequest(new LongRunningRequest(e, DatasetInfo.class, requester));
            return checkLongRunningRequestStatus();
        }
    }

    /**
     * Makes a dataset public.  After calling this on a dataset, it will be visible to
     * any user, meaning any use will have teh "viewer" role for this dataset.
     *
     * @param datasetId id of the dataset to make public.
     */
    public void makePublic(final String datasetId) throws SodaError, InterruptedException {
        SodaRequest requester = new SodaRequest<String>(datasetId,null)
        {

            //accessType=WEBSITE&method=setPermission&value=public.read
            public ClientResponse issueRequest() throws LongRunningQueryException, SodaError
            {
                final URI publicationUri = UriBuilder.fromUri(viewUri)
                                                     .path(resourceId)
                                                     .queryParam("accessType", "WEBSITE")
                                                     .queryParam("method", "setPermission")
                                                     .queryParam("value", "public.read")
                                                     .build();

                return httpLowLevel.putRaw(publicationUri, HttpLowLevel.JSON_TYPE, "method=setPermission");
            }
        };

        try {

            requester.issueRequest();
        } catch (LongRunningQueryException e) {
            getHttpLowLevel().getAsyncResults(e.location, e.timeToRetry, getHttpLowLevel().getMaxRetries(), Dataset.class, requester);
        }
    }


    /**
     * Makes a dataset private, so it can only be viewed by users that it has been shared
     * with, or people who are admins on the site.
     *
     * @param datasetId id of the dataset
     */
    public void makePrivate(final String datasetId) throws SodaError, InterruptedException {
        SodaRequest requester = new SodaRequest<String>(datasetId,null)
        {

            //accessType=WEBSITE&method=setPermission&value=public.read
            public ClientResponse issueRequest() throws LongRunningQueryException, SodaError
            {
                final URI publicationUri = UriBuilder.fromUri(viewUri)
                                                     .path(resourceId)
                                                     .queryParam("accessType", "WEBSITE")
                                                     .queryParam("method", "setPermission")
                                                     .queryParam("value", "private")
                                                     .build();

                return httpLowLevel.putRaw(publicationUri, HttpLowLevel.JSON_TYPE, "method=setPermission");
            }
        };

        try {

            requester.issueRequest();
        } catch (LongRunningQueryException e) {
            getHttpLowLevel().getAsyncResults(e.location, e.timeToRetry, getHttpLowLevel().getMaxRetries(), Dataset.class, requester);
        }
    }

    /**
     * Waits for pending geocodes to be finished.  A publish won't work until all active geocoding requests are
     * handled.
     *
     * @param datasetId id of the dataset to check for outstanding geocodes.
     * @throws InterruptedException
     * @throws SodaError
     */
    public void waitForPendingGeocoding(final String datasetId) throws InterruptedException, SodaError
    {
        GeocodingResults geocodingResults = findPendingGeocodingResults(datasetId);
        while (geocodingResults.getView() > 0)
        {
            try { Thread.sleep(TICKET_CHECK); } catch (InterruptedException e) {}
            geocodingResults = findPendingGeocodingResults(datasetId);
        }
    }

    /**
     * Checks to see if the current dataset has any pending Geocoding results.
     *
     * @param datasetId id of the dataset
     * @return The Geocoding results for this dataset.
     *
     * @throws SodaError
     * @throws InterruptedException
     */
    public GeocodingResults findPendingGeocodingResults(final String datasetId) throws SodaError, InterruptedException
    {
        SodaRequest requester = new SodaRequest<String>(datasetId, null)
        {
            public ClientResponse issueRequest() throws LongRunningQueryException, SodaError
            {
                final URI uri = UriBuilder.fromUri(geocodingUri)
                                          .path(datasetId)
                                          .queryParam("method", "pending")
                                          .build();

                return httpLowLevel.queryRaw(uri, HttpLowLevel.JSON_TYPE);
            }
        };


        try {
            final ClientResponse response = requester.issueRequest();
            return response.getEntity(GeocodingResults.class);
        } catch (LongRunningQueryException e) {
            return getHttpLowLevel().getAsyncResults(e.location, e.timeToRetry, getHttpLowLevel().getMaxRetries(), GeocodingResults.class, requester);
        }
    }

    /**
     * Check status of a long running request.
     * @param <T>
     * @param <R>
     * @return Final result from the long running request
     * @throws SodaError
     * @throws InterruptedException
     */
    public <T, R> R checkLongRunningRequestStatus() throws SodaError, InterruptedException {
        LongRunningRequest<T, R> lrr = getLastLongRunningRequest();
        assert(lrr != null);
        R result = lrr.checkStatus(httpLowLevel);
        setLastLongRunningRequest(null); // Clear long running request.
        return result;
    }

    /**
     * Get the last long running request.
     * Once we successfully get the final result, long running request should be cleared.
     * @return
     */
    public LongRunningRequest getLastLongRunningRequest()
    {
        return lastLongRunningRequest;
    }

    /**
     * Save long running request from server or clear it.
     * @param lastLongRunningRequest - long running request from server or null.
     */
    protected void setLastLongRunningRequest(LongRunningRequest lastLongRunningRequest)
    {
        this.lastLongRunningRequest = lastLongRunningRequest;
    }
}
