package com.softropic.skillars.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DbCleaner {

    @Autowired
    protected TransactionTemplate template;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    //Let tests determine if this is needed in before or after methods
    // e.g. It cannot be used as @BeforeEach for methods that load sql statements
    // If you want test data to persist after a test, you cannot also use @AfterEach
    public void cleanDb() {
        template.execute(status ->  {
            jdbcTemplate.execute("delete from main.persistent_token");
            jdbcTemplate.execute("delete from main.user_authority");
            jdbcTemplate.execute("delete from main.audit_log");
            jdbcTemplate.execute("delete from main.user_addresses");
            jdbcTemplate.execute("delete from main.user");
            jdbcTemplate.execute("delete from main.authority");
            //jdbcTemplate.execute("delete from main.inventory");
            //jdbcTemplate.execute("delete from main.inventory_log");
            //jdbcTemplate.execute("delete from main.payment");
            //jdbcTemplate.execute("delete from main.mobile_pay_data");
            //jdbcTemplate.execute("delete from main.delivery_order_item_ids");
            jdbcTemplate.execute("delete from main.envelope_entity_recipients");
            jdbcTemplate.execute("delete from main.envelope_entity");
            jdbcTemplate.execute("delete from main.sec");

            return 0;
        });
    }

}
