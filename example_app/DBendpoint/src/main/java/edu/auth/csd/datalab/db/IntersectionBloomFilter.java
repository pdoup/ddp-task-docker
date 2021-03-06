package edu.auth.csd.datalab.db;

import java.util.List;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.base.Charsets;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;

import edu.auth.csd.datalab.db.utils.models.MyIgnite;
import edu.auth.csd.datalab.db.utils.models.MyRedis;
import edu.auth.csd.datalab.db.utils.models.MyBloomFilter;

public class IntersectionBloomFilter {

    private BloomFilter<String> bloomFilterR;
    private BloomFilter<String> bloomFilterL;
    private MyBloomFilter bloomFilterR2;
    private MyBloomFilter bloomFilterL2;
    private static MyIgnite ignite;
    private static MyRedis redis;
    private static Logger logger = null;
    private static IntersectionBloomFilter intersectionBloomFilter = null;
    private final int capacity;
    private final float fpp;
    private static int counter = 0;

    static {
        final String logLevel = System.getProperty("logLevel") != null ? System.getProperty("logLevel") : "INFO";
        logger = Logger.getLogger(SemiJoin.class.getName());
        logger.setLevel(Level.parse(logLevel));
    }
    
    // Construct the 2 Bloom filters assuming a default 1% false positive rate
    private IntersectionBloomFilter(MyIgnite ignite_i, MyRedis redis_i, int capacity_) {
        ignite = ignite_i;
        redis = redis_i;
        capacity = capacity_;
        fpp = .01f; // 1% false positive rate by default
        logger.info("=================== Intersection Bloom Join ===================");
        logger.info("Setting initial capacity to " + capacity + " elements");
        logger.info("Accepting " + fpp * 100 + "% false positive probability");
        
        // Using my implementation of a bloom filter
        bloomFilterL2 = new MyBloomFilter(capacity, fpp);
        bloomFilterR2 = new MyBloomFilter(capacity, fpp);
        
        // Using Guavas' approach
        bloomFilterL = BloomFilter.create(Funnels.stringFunnel(Charsets.UTF_8), capacity, fpp);
        bloomFilterR = BloomFilter.create(Funnels.stringFunnel(Charsets.UTF_8), capacity, fpp);
    }
    
    // Same as above but fpp can be set
    private IntersectionBloomFilter(MyIgnite ignite_i, MyRedis redis_i, int capacity_, float fpp_) {
        ignite = ignite_i;
        redis = redis_i;
        capacity = capacity_;
        fpp = fpp_;
        logger.info("=================== Intersection Bloom Join ===================");
        logger.info("Setting initial capacity to " + capacity + " elements");
        logger.info("Accepting " + fpp * 100 + "% false positive probability");
        
        bloomFilterL2 = new MyBloomFilter(capacity, fpp);
        bloomFilterR2 = new MyBloomFilter(capacity, fpp);

        bloomFilterL = BloomFilter.create(Funnels.stringFunnel(Charsets.UTF_8), capacity, fpp);
        bloomFilterR = BloomFilter.create(Funnels.stringFunnel(Charsets.UTF_8), capacity, fpp);
    }

    public static IntersectionBloomFilter getInstance(final MyIgnite ignite_i, final MyRedis redis_i,
            final int capacity) {
        if (intersectionBloomFilter == null) {
            intersectionBloomFilter = new IntersectionBloomFilter(ignite_i, redis_i, capacity);
        }
        return intersectionBloomFilter;
    }

    public static IntersectionBloomFilter getInstance(final MyIgnite ignite_i, final MyRedis redis_i,
            final int capacity, final float fpp) {
        if (intersectionBloomFilter == null) {
            intersectionBloomFilter = new IntersectionBloomFilter(ignite_i, redis_i, capacity, fpp);
        }
        return intersectionBloomFilter;
    }
    
    // Add list of keys to bloom filter (Guava)
    public static void fillBF(final List<String> keys, BloomFilter<String> bf) {
        for (final String key : keys) {
            bf.put(key);
        }
    }
    
    // Add list of keys to bloom filter
    public static void fillBF(final List<String> keys, MyBloomFilter bf) {
        for (final String key : keys) {
            bf.addToBloomFilter(key);
        }
    }
    
    // Filter predicate to perform logical AND to 2 BF
    public Predicate<String> intersectionPredicateBF() {
        return bloomFilterL.and(bloomFilterR);
    }
    
    // Intersection BF join using Guava
    public void doIntersectionBFJoin1() {
        logger.info("==== Using Guava's implemetation of Bloom Filter ====");
        // Get keys from both DBs
        List<String> redisKeys = redis.getAllKeys();
        List<String> igniteKeys = ignite.getAllKeys();
        
        // Add set of keys to respective BF
        fillBF(redisKeys, bloomFilterL);
        fillBF(igniteKeys, bloomFilterR);
        
        Predicate<String> intersectionBF = intersectionPredicateBF();

        /* 
        Remove all keys that do NOT meet the predicate, meaning all keys
        that don't have same bits set in their BFs' thus filtering keys
        that are surely not in the join result set
        */
        redisKeys.removeIf(intersectionBF.negate());
        igniteKeys.removeIf(intersectionBF.negate());

        counter = 0;
        // Do a nested-loop join for the remaining keys and show the results
        for (final String key : redisKeys) {
            for (final String igniteKey : igniteKeys) {
                if (igniteKey.equals(key)) {
                    logger.info(String.format("Got match => (%s, %s, %s)", key, redis.getData(key),
                            ignite.getData(key)));
                    ++counter;
                    break; // exit inner loop since keys are unique, no other match will prevail
                }
            }
        }
    }
    
    // Applies the same logic as above using my own Bloom Filter implementation
    public void doIntersectionBFJoin2() {
        logger.info("==== Using my own implementation of Bloom Filter ====");
        List<String> redisKeys = redis.getAllKeys();
        List<String> igniteKeys = ignite.getAllKeys();

        fillBF(redisKeys, bloomFilterL2);
        fillBF(igniteKeys, bloomFilterR2);

        MyBloomFilter intersectionBF = (MyBloomFilter) bloomFilterL2.clone();
        
        intersectionBF.getBloomFilter().and(bloomFilterR2.getBloomFilter());

        redisKeys.removeIf(key -> !intersectionBF.testKeyInBloomFilter(key));
        igniteKeys.removeIf(key -> !intersectionBF.testKeyInBloomFilter(key));

        counter = 0;
        for (final String key : redisKeys) {
            for (final String igniteKey : igniteKeys) {
                if (igniteKey.equals(key)) {
                    logger.info(String.format("Got match => (%s, %s, %s)", key, redis.getData(key),
                            ignite.getData(key)));
                    ++counter;
                    break; // exit inner loop since keys are unique, no other match will prevail
                }
            }
        }
    }

    public int getCounter() {
        return counter;
    }
}
