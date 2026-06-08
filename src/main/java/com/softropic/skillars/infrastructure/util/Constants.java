package com.softropic.skillars.infrastructure.util;

/**
 * Fields exposed across various packages
 */
public final class Constants {

    public static final String SPRING_PROFILE_PRODUCTION = "prod";
    public static final String SPRING_PROFILE_DEVELOPMENT = "dev";
    public static final String HEARTBEAT_OK = "OK";
    public static final String REQUEST_ID_HEADER_NAME = "X-Request-Id";
    public static final String REQUEST_ID_NAME = "requestId";
    public static final String TXN_ID_NAME = "transactionId";
    public static final String ENTITY_NAME = "entityName";
    public static final String SMARTIX_CONFIG_NAME = "smartixConfig";
    public static final String HTTP_REQUEST_ID_DELIM = "REQTXN";


    /*******************************************************************************************************************
     *
     *                            Unique key constraints
     *
     *******************************************************************************************************************/

    public static final String UK_ROUTE_BUSINESSID = "UK_route_businessId";
    public static final String UK_TICKET_ORDER_TXN_ID = "UK_ticketOrder_txnId";
    public static final String UK_AGENCY_NAME = "UK_agency_name";
    public static final String UK_TICKETCATALOG_AGENCYID_ROUTEID = "UK_ticketCatalog_agencyId_routeId";
    public static final String UK_CUSTOMER_IDNO = "UK_customer_idNo";
    public static final String UK_USER_LOGIN = "UK_user_login";
    public static final String UK_USER_EMAIL = "UK_user_email";
    public static final String UK_TERMINAL_NAME = "UK_terminal_name";

    private Constants() {}
}
