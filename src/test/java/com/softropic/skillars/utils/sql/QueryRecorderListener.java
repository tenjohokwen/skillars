package com.softropic.skillars.utils.sql;

import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.QueryType;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.listener.QueryUtils;

import java.util.List;

public class QueryRecorderListener implements QueryExecutionListener {
    @Override
    public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {

    }

    @Override
    public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        for (QueryInfo queryInfo : queryInfoList) {
            final String query = queryInfo.getQuery();
            final QueryType type = QueryUtils.getQueryType(query);
            registerQuery(type, query);
        }
    }

    private void registerQuery(QueryType type, String query) {
        final Statement statement = SqlStatementHolder.getStatement();
        switch (type) {
            case INSERT -> statement.registerInsert(query);
            case SELECT -> statement.registerSelect(query);
            case UPDATE -> statement.registerUpdate(query);
            case DELETE -> statement.registerDelete(query);
            case OTHER -> {}
        }
    }


}
