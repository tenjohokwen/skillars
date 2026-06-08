package com.softropic.skillars.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContextException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jakarta.annotation.PostConstruct;


/**
 * For the actual environment, this class ensures that the configured db schema is up-to-date
 * It uses db versioning scripts in order to make the decision.
 */
//TODO you have to do the flyway config in such a way that it is added to the management endpoint (actuator??)
@Component
@ConditionalOnProperty(name="custom.flyway.check-schema", havingValue = "true")
public class DbSchemaChecker {

    @Autowired
    private Flyway flyway;

    //@Autowired
    //private DataSource dataSource;

    @PostConstruct //TODO try moving this to the parameterless constructor and taking off this annotation
    private void init() { //NOPMD
        //flyway = Flyway.configure().dataSource(dataSource).load();
        validateDbSchema();
    }

    private void validateDbSchema() {
        final MigrationInfo[] pending = flyway.info().pending();
        if (pending.length > 0) {
            final List<String> scripts = new ArrayList<>(pending.length);
            Arrays.stream(pending).forEach(action -> scripts.add(action.getScript()));
            throw new ApplicationContextException("The database still has pending updates: " + scripts);
        }
    }

}
