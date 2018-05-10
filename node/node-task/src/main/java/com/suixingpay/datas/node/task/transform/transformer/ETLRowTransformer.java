/**
 * All rights Reserved, Designed By Suixingpay.
 *
 * @author: zhangkewei[zhang_kw@suixingpay.com]
 * @date: 2017年12月28日 13:38
 * @Copyright ©2017 Suixingpay. All rights reserved.
 * 注意：本内容仅限于随行付支付有限公司内部传阅，禁止外泄以及用于其他的商业用途。
 */

package com.suixingpay.datas.node.task.transform.transformer;

import com.alibaba.fastjson.JSON;
import com.suixingpay.datas.common.db.meta.TableColumn;
import com.suixingpay.datas.common.db.meta.TableSchema;
import com.suixingpay.datas.common.exception.TaskStopTriggerException;
import com.suixingpay.datas.node.core.event.etl.ETLBucket;
import com.suixingpay.datas.node.core.event.etl.ETLColumn;
import com.suixingpay.datas.node.core.event.etl.ETLRow;
import com.suixingpay.datas.node.core.event.s.EventType;
import com.suixingpay.datas.node.core.loader.DataLoader;
import com.suixingpay.datas.node.core.task.TableMapper;
import com.suixingpay.datas.node.task.worker.TaskWork;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author: zhangkewei[zhang_kw@suixingpay.com]
 * @date: 2017年12月28日 13:38
 * @version: V1.0
 * @review: zhangkewei[zhang_kw@suixingpay.com]/2017年12月28日 13:38
 */
public class ETLRowTransformer implements Transformer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ETLRowTransformer.class);

    @Override
    public int order() {
        return 0;
    }

    @Override
    public void transform(ETLBucket bucket, TaskWork work) throws Exception {
        LOGGER.debug("start tranforming bucket:{},size:{}", bucket.getSequence(), bucket.getRows().size());
        for (ETLRow row : bucket.getRows()) {
            LOGGER.debug("try tranform row:{},{}", row.getPosition().render(), JSON.toJSONString(row));
            //表映射
            TableMapper tableMapper = work.getTableMapper(row.getFinalSchema(), row.getFinalTable());
            mappingRowData(tableMapper, row);
            //目标端表结构元数据
            TableSchema table = findTable(work.getDataLoader(), row.getFinalSchema(), row.getFinalTable());
            if (null != tableMapper && tableMapper.isIgnoreTargetCase()) table.toUpperCase();

            /**
             * 数据库元数据正反向映射
             * 先根据table是否为null执行remedyColumns方法判断是否发生字段数量变更，
             * 最后根据tableMapper配置判断是否要求源端与目标端字段强一致
             */
            if (null != table && remedyColumns(table, row) && (null == tableMapper || tableMapper.isForceMatched())) {
                throw new TaskStopTriggerException("目标端与源端表结构不一致。"
                        + "映射表:" + JSON.toJSONString(tableMapper) + ",目标端表:" + JSON.toJSONString(table));
            }

            /**
             * 为了减少表结构造成的数据问题，增加人工介入机会。
             * 默认TableMapper为绝对正确的输入，当前ETLRow数据类型为Insert时，如果有不存在预配置字段项的映射，任务停止，人工介入
             */
            if (row.getFinalOpType() == EventType.INSERT && null != tableMapper && null != tableMapper.getColumn()
                    && !tableMapper.getColumn().isEmpty()) {
                for (String columnName : tableMapper.getColumn().values()) {
                    //最终字段与映射表匹配数量
                    long matchCount = row.getColumns().stream().filter(c -> c.getFinalName().equalsIgnoreCase(columnName)).count();
                    if (matchCount < 1) {
                        throw new TaskStopTriggerException("映射表与目标端表结构不一致。映射表:"
                                + JSON.toJSONString(tableMapper) + ",目标端表结构:" + JSON.toJSONString(table));
                    }
                }
            }


            //当是更新时，判断主键是否变更
            if (row.getFinalOpType() == EventType.UPDATE) {
                boolean isChanged = !row.getColumns().stream().filter(c -> c.isKey()
                        && !StringUtils.trimToEmpty(c.getFinalOldValue()).equals(StringUtils.trimToEmpty(c.getFinalValue())))
                        .collect(Collectors.toList()).isEmpty();
                row.setKeyChangedOnUpdate(isChanged);
            }

            //DataLoader自定义处理
            work.getDataLoader().mouldRow(row);

            LOGGER.debug("after tranform row:{},{}", row.getPosition().render(), JSON.toJSONString(row));
        }
    }

    private void mappingRowData(TableMapper mapper, ETLRow row) {
        if (null != mapper) {
            //替换schema和表名
            if (null != mapper.getSchema() && mapper.getSchema().length == 2) row.setFinalSchema(mapper.getSchema()[1]);
            if (null != mapper.getTable() && mapper.getTable().length == 2) row.setFinalTable(mapper.getTable()[1]);
            if (null != row.getColumns() && null != mapper.getColumn()) {
                for (ETLColumn c : row.getColumns()) {
                    //替换字段名
                    c.setFinalName(mapper.getColumn().getOrDefault(c.getFinalName(), c.getFinalName()));
                }
            }
        }
    }

    private boolean remedyColumns(TableSchema table, ETLRow row) {
        List<ETLColumn> removeables = new ArrayList<>();
        //正向查找
        for (ETLColumn c : row.getColumns()) {
            TableColumn column = table.findColumn(c.getFinalName());
            if (null != column) {
                c.setFinalType(column.getTypeCode());
                c.setKey(column.isPrimaryKey());
                c.setRequired(column.isRequired());

                //如果是更新且字段必填，更新前的值不存在
                //if (row.getOpType() == EventType.UPDATE && column.isRequired() && StringUtils.isBlank(c.getOldValue())) {
                //    c.setOldValue(c.getNewValue());
                //}

            } else {
                removeables.add(c);
            }
        }

        //反向查找
        List<String> inColumnNames = row.getColumns().stream().map(p  -> p.getFinalName()).collect(Collectors.toList());

        /**
         * 如果目标库表中有新增必填的字段的话需要补充上去
         */
        //table.getColumns().stream().filter(p -> !inColumnNames.contains(p.getName()) && p.isRequired() && null == p.getDefaultValue())
        List<TableColumn> targetNew = table.getColumns().stream().filter(p -> !inColumnNames.contains(p.getName()) && p.isRequired())
                .collect(Collectors.toList());
        targetNew.forEach(p -> {
            ETLColumn column = new ETLColumn(false, false, p.getName(), p.getDefaultValue(),
                    p.getDefaultValue(), p.getDefaultValue(), p.isPrimaryKey(), p.isRequired(), p.getTypeCode());
            row.getAdditionalRequired().add(column);
        });

        row.getColumns().removeAll(removeables);

        return !removeables.isEmpty();
    }

    private TableSchema findTable(DataLoader loader, String finalSchema, String finalTable)
            throws TaskStopTriggerException {
        TableSchema table = null;
        try {
            table  = loader.findTable(finalSchema, finalTable);
        } catch (Throwable e) {
            String error = "查询不到目标仓库表结构" + finalSchema + ". " + finalTable;
            //e.printStackTrace();
            LOGGER.error(error, e);
            //if (TaskStopTriggerException.isMatch(e))
            throw new TaskStopTriggerException(error + ";error:" + e.getMessage());
        }
        return table;
    }
}
