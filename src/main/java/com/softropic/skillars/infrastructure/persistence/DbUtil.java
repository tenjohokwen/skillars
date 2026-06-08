package com.softropic.skillars.infrastructure.persistence;

import io.hypersistence.tsid.TSID.Factory;

/**
 *
 */
public class DbUtil {
    private static final Factory DEFAULT_TSID_FACTORY = Factory.builder()
                                                               .withRandomFunction(Factory.THREAD_LOCAL_RANDOM_FUNCTION)
                                                               .build();

    private DbUtil() {}
    /**
     * Use this class to generate a time-sorted unique identifier (TSID)
     * Use this because the UUID is not sortable (bad for indexing) and it requires much storage allocation when stored in the db
     * For indexes ordering matters
     * Have a look at https://github.com/f4b6a3/tsid-creator, https://github.com/f4b6a3/ulid-creator
     * and https://github.com/f4b6a3/uuid-creator/blob/master/src/main/java/com/github/f4b6a3/uuid/alt/GUID.java
     * @return
     */
    public static Long generateDbRandom() {
        return DEFAULT_TSID_FACTORY.generate().toLong();
    }
}
