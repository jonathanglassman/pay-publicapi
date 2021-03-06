package uk.gov.pay.api.filter.ratelimit;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.HttpMethod;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class LocalRateLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalRateLimiter.class);

    private final int noOfReqPerNode;
    private final int noOfReqForPostPerNode;
    private final int perMillis;

    private final Cache<String, RateLimit> cache;


    public LocalRateLimiter(int noOfReqPerNode, int noOfReqForPostPerNode, int perMillis) {
        this.noOfReqPerNode = noOfReqPerNode;
        this.noOfReqForPostPerNode = noOfReqForPostPerNode;
        this.perMillis = perMillis;

        this.cache = CacheBuilder.newBuilder()
                .expireAfterAccess(perMillis, TimeUnit.MILLISECONDS)
                .build();
    }

    void checkRateOf(String key, String method) throws RateLimitException {
        try {
            cache.get(key, () -> new RateLimit(getNoOfRequestsForMethod(method), perMillis)).updateAllowance();
        } catch (ExecutionException e) {
            //ExecutionException is thrown when the valueLoader (cache.get())  throws a checked exception.
            //We just create a new instance (RateLimit) so no exceptions will be thrown, this should never happen.
            LOGGER.error("Unexpected error creating a Rate Limiter object in cache", e);
        }
    }
    
    private int getNoOfRequestsForMethod(String method) {
        if (HttpMethod.POST.equals(method)) {
            return noOfReqForPostPerNode;
        }
        return noOfReqPerNode;
    }


}
