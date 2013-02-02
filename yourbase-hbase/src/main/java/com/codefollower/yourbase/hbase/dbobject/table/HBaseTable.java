/*
 * Copyright 2011 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.codefollower.yourbase.hbase.dbobject.table;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.master.HMaster;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.zookeeper.ZKTableReadOnly;

import com.codefollower.yourbase.command.Prepared;
import com.codefollower.yourbase.command.ddl.CreateTableData;
import com.codefollower.yourbase.constant.ErrorCode;
import com.codefollower.yourbase.dbobject.Schema;
import com.codefollower.yourbase.dbobject.index.Index;
import com.codefollower.yourbase.dbobject.index.IndexType;
import com.codefollower.yourbase.dbobject.table.Column;
import com.codefollower.yourbase.dbobject.table.IndexColumn;
import com.codefollower.yourbase.dbobject.table.TableBase;
import com.codefollower.yourbase.engine.Session;
import com.codefollower.yourbase.hbase.command.ddl.Options;
import com.codefollower.yourbase.hbase.dbobject.index.HBaseTableIndex;
import com.codefollower.yourbase.hbase.engine.HBaseDatabase;
import com.codefollower.yourbase.hbase.engine.HBaseSession;
import com.codefollower.yourbase.hbase.result.HBaseRow;
import com.codefollower.yourbase.hbase.util.HBaseUtils;
import com.codefollower.yourbase.message.DbException;
import com.codefollower.yourbase.result.Row;
import com.codefollower.yourbase.result.RowList;
import com.codefollower.yourbase.util.New;
import com.codefollower.yourbase.util.StatementBuilder;
import com.codefollower.yourbase.value.Value;

public class HBaseTable extends TableBase {
    private static final String STATIC_TABLE_DEFAULT_COLUMN_FAMILY_NAME = "CF";
    private final ArrayList<Index> indexes = New.arrayList();
    private HTableDescriptor hTableDescriptor;
    private String rowKeyName;
    private Index scanIndex;
    private Map<String, ArrayList<Column>> columnsMap;
    private boolean modified;
    private Column rowKeyColumn;
    private boolean isStatic;

    public boolean isStatic() {
        return isStatic;
    }

    public boolean isModified() {
        return modified;
    }

    public void setModified(boolean modified) {
        this.modified = modified;
    }

    public Column getRowKeyColumn() {
        if (rowKeyColumn == null) {
            rowKeyColumn = new Column(getRowKeyName(), true);
            rowKeyColumn.setTable(this, -2);
        }
        return rowKeyColumn;
    }

    public HBaseTable(CreateTableData data) {
        super(data);
        if (data.cloningTable != null) {
            HBaseTable t = (HBaseTable) data.cloningTable;
            hTableDescriptor = t.hTableDescriptor;
            rowKeyName = t.rowKeyName;
            rowKeyColumn = t.rowKeyColumn;
            if (rowKeyColumn != null)
                rowKeyColumn.setTable(this, rowKeyColumn.getColumnId());
            isStatic = t.isStatic;

            rebuildColumnsMap(getColumns(), t.getDefaultColumnFamilyName());
        } else {
            isStatic = true;
            Column[] cols = getColumns();
            for (Column c : cols) {
                //if (c.isPrimaryKey()) { //在Parser.parseColumn中设为false了不能这么用
                if (c.isRowKeyColumn()) {
                    rowKeyColumn = c.getClone();
                    rowKeyColumn.setTable(this, -2);
                    rowKeyColumn.setRowKeyColumn(true);
                    rowKeyName = rowKeyColumn.getName();
                }

                c.setColumnFamilyName(STATIC_TABLE_DEFAULT_COLUMN_FAMILY_NAME);
            }
            //        if (rowKeyColumn == null) {
            //            throw new RuntimeException("static type table '" + data.tableName + "' not found a primary key");
            //        }
            setColumns(cols);

            hTableDescriptor = new HTableDescriptor(data.tableName);
            if (rowKeyColumn != null)
                hTableDescriptor.setValue(Options.ON_ROW_KEY_NAME, rowKeyName);
            hTableDescriptor.setValue(Options.ON_DEFAULT_COLUMN_FAMILY_NAME, STATIC_TABLE_DEFAULT_COLUMN_FAMILY_NAME);
            hTableDescriptor.addFamily(new HColumnDescriptor(STATIC_TABLE_DEFAULT_COLUMN_FAMILY_NAME));

            createIfNotExists(data.session, data.tableName, hTableDescriptor, null);
        }

        scanIndex = new HBaseTableIndex(this, data.id, IndexColumn.wrap(getColumns()), IndexType.createScan(false));
        indexes.add(scanIndex);
    }

    public HBaseTable(Session session, Schema schema, int id, String name, Map<String, ArrayList<Column>> columnsMap,
            ArrayList<Column> columns, HTableDescriptor htd, byte[][] splitKeys) {
        super(schema, id, name, true, true, HBaseTableEngine.class.getName(), false);
        this.columnsMap = columnsMap;

        Column[] cols = new Column[columns.size()];
        columns.toArray(cols);
        setColumns(cols);
        scanIndex = new HBaseTableIndex(this, id, IndexColumn.wrap(getColumns()), IndexType.createScan(false));
        hTableDescriptor = htd;
        createIfNotExists(session, name, htd, splitKeys);
        indexes.add(scanIndex);
    }

    public String getDefaultColumnFamilyName() {
        if (isStatic)
            return STATIC_TABLE_DEFAULT_COLUMN_FAMILY_NAME;
        else
            return hTableDescriptor.getValue(Options.ON_DEFAULT_COLUMN_FAMILY_NAME);
    }

    public void setRowKeyName(String rowKeyName) {
        this.rowKeyName = rowKeyName;
    }

    public String getRowKeyName() {
        if (rowKeyName == null) {
            if (isStatic) {
                for (Index idx : getIndexes()) {
                    if (idx.getIndexType().isPrimaryKey() || idx.getIndexType().isUnique()) {
                        if (idx.getIndexColumns().length == 1) {
                            rowKeyColumn = idx.getIndexColumns()[0].column.getClone();
                            rowKeyColumn.setTable(this, -2);
                            rowKeyColumn.setRowKeyColumn(true);
                            rowKeyName = rowKeyColumn.getName();
                            break;
                        }
                    }
                }
            }
            if (rowKeyName == null)
                rowKeyName = Options.DEFAULT_ROW_KEY_NAME;
        }

        return rowKeyName;
    }

    @Override
    public void lock(Session session, boolean exclusive, boolean force) {

    }

    @Override
    public void close(Session session) {

    }

    @Override
    public void unlock(Session s) {

    }

    @Override
    public Index addIndex(Session session, String indexName, int indexId, IndexColumn[] cols, IndexType indexType,
            boolean create, String indexComment) {
        //new Error("id:"+indexId+" "+ indexName).printStackTrace();
        if (indexType.isPrimaryKey()) {
            for (IndexColumn c : cols) {
                Column column = c.column;
                if (column.isNullable()) {
                    throw DbException.get(ErrorCode.COLUMN_MUST_NOT_BE_NULLABLE_1, column.getName());
                }
                column.setPrimaryKey(true);
            }
        }
        boolean isSessionTemporary = isTemporary() && !isGlobalTemporary();
        if (!isSessionTemporary) {
            database.lockMeta(session);
        }
        Index index = new HBaseTableIndex(this, indexId, indexName, cols, indexType);

        index.setTemporary(isTemporary());
        if (index.getCreateSQL() != null) {
            index.setComment(indexComment);
            if (isSessionTemporary) {
                session.addLocalTempTableIndex(index);
            } else {
                database.addSchemaObject(session, index);
            }
        }
        setModified();
        indexes.add(index);
        return index;
    }

    @Override
    public void addRow(Session session, Row row) {
        for (int i = 0, size = indexes.size(); i < size; i++) {
            Index index = indexes.get(i);
            index.add(session, row);
        }
    }

    @Override
    public void updateRows(Prepared prepared, Session session, RowList rows) {
        Column[] columns = getColumns();
        int columnCount = columns.length;
        Put put;
        Column c;
        for (rows.reset(); rows.hasNext();) {
            HBaseRow o = (HBaseRow) rows.next();
            HBaseRow n = (HBaseRow) rows.next();

            o.setForUpdate(true);
            n.setRegionName(o.getRegionName());
            n.setRowKey(o.getRowKey());
            put = new Put(HBaseUtils.toBytes(n.getRowKey()));
            for (int i = 0; i < columnCount; i++) {
                c = columns[i];
                put.add(c.getColumnFamilyNameAsBytes(), c.getNameAsBytes(), HBaseUtils.toBytes(n.getValue(i)));
                n.setPut(put);
            }
        }
        super.updateRows(prepared, session, rows);
    }

    @Override
    public void removeRow(Session session, Row row) {
        for (int i = indexes.size() - 1; i >= 0; i--) {
            Index index = indexes.get(i);
            index.remove(session, row);
        }
    }

    @Override
    public void truncate(Session session) {

    }

    @Override
    public void checkSupportAlter() {

    }

    @Override
    public String getTableType() {
        return "HBASE TABLE";
    }

    @Override
    public Index getScanIndex(Session session) {
        return scanIndex;
    }

    @Override
    public Index getUniqueIndex() {
        return scanIndex;
    }

    @Override
    public ArrayList<Index> getIndexes() {
        return indexes;
    }

    @Override
    public boolean isLockedExclusively() {
        return false;
    }

    @Override
    public long getMaxDataModificationId() {
        return 0;
    }

    @Override
    public boolean isDeterministic() {
        return false;
    }

    @Override
    public boolean canGetRowCount() {
        return false;
    }

    @Override
    public boolean canDrop() {
        return true;
    }

    @Override
    public long getRowCount(Session session) {
        return 0;
    }

    @Override
    public long getRowCountApproximation() {
        return 0;
    }

    @Override
    public String getCreateSQL() {
        if (isStatic)
            return super.getCreateSQL();
        StatementBuilder buff = new StatementBuilder("CREATE HBASE TABLE IF NOT EXISTS ");
        buff.append(getSQL());
        buff.append("(\n");
        buff.append("    OPTIONS(");
        boolean first = true;
        for (Entry<ImmutableBytesWritable, ImmutableBytesWritable> e : hTableDescriptor.getValues().entrySet()) {
            //buff.appendExceptFirst(",");
            if (first) {
                first = false;
            } else {
                buff.append(",");
            }
            buff.append(toS(e.getKey())).append("='").append(toS(e.getValue())).append("'");
        }
        buff.append(")");
        HColumnDescriptor[] hcds = hTableDescriptor.getColumnFamilies();
        String cfName;
        if (hcds != null && hcds.length > 0) {
            for (HColumnDescriptor hcd : hcds) {
                cfName = hcd.getNameAsString();
                buff.append(",\n    COLUMN FAMILY ").append(cfName).append(" (\n");
                buff.append("        OPTIONS(");
                first = true;
                for (Entry<ImmutableBytesWritable, ImmutableBytesWritable> e : hcd.getValues().entrySet()) {
                    if (first) {
                        first = false;
                    } else {
                        buff.append(",");
                    }
                    buff.append(toS(e.getKey())).append("='").append(toS(e.getValue())).append("'");
                }
                buff.append(")"); //OPTIONS
                if (columnsMap.get(cfName) != null) {
                    for (Column column : columnsMap.get(cfName)) {
                        buff.append(",\n        ");
                        buff.append(column.getCreateSQL());
                    }
                }
                buff.append("\n    )"); //COLUMN FAMILY
            }
        }
        buff.append("\n)"); //CREATE HBASE TABLE
        return buff.toString();
    }

    @Override
    public void checkRename() {
        throw DbException.getUnsupportedException("HBase Table"); //HBase的表不支持重命名
    }

    @Override
    public Column getColumn(String columnName) {
        if (getRowKeyName().equalsIgnoreCase(columnName))
            return getRowKeyColumn();

        if (columnName.indexOf('.') == -1)
            return super.getColumn(getFullColumnName(null, columnName));
        else
            return super.getColumn(columnName);
    }

    public String getFullColumnName(String columnFamilyName, String columnName) {
        if (columnFamilyName == null)
            columnFamilyName = getDefaultColumnFamilyName();
        return columnFamilyName + "." + columnName;
    }

    public Column getColumn(String columnFamilyName, String columnName, boolean isInsert) {
        if (getRowKeyName().equalsIgnoreCase(columnName))
            return getRowKeyColumn();

        columnName = getFullColumnName(columnFamilyName, columnName);
        if (!isStatic && isInsert) {
            if (doesColumnExist(columnName))
                return super.getColumn(columnName);
            else { //处理动态列
                Column[] oldColumns = getColumns();
                Column[] newColumns;
                if (oldColumns == null)
                    newColumns = new Column[1];
                else
                    newColumns = new Column[oldColumns.length + 1];
                System.arraycopy(oldColumns, 0, newColumns, 0, oldColumns.length);
                Column c = new Column(columnName);
                newColumns[oldColumns.length] = c;
                setColumnsNoCheck(newColumns);
                modified = true;

                ArrayList<Column> list = columnsMap.get(c.getColumnFamilyName());
                if (list == null) {
                    list = New.arrayList();
                    columnsMap.put(c.getColumnFamilyName(), list);
                }
                list.add(c);
                return c;
            }
        } else {
            return super.getColumn(columnName);
        }
    }

    @Override
    public Row getTemplateRow() {
        return new HBaseRow(new Value[columns.length], Row.MEMORY_CALCULATE);
    }

    @Override
    public void removeChildrenAndResources(Session session) {
        super.removeChildrenAndResources(session);
        if (!((HBaseDatabase) database).isFromZookeeper())
            dropIfExists(session, getName());
    }

    private static void createIfNotExists(Session session, String tableName, HTableDescriptor htd, byte[][] splitKeys) {
        try {
            HMaster master = ((HBaseSession) session).getMaster();
            if (master != null && master.getTableDescriptors().get(tableName) == null) {
                master.createTable(htd, splitKeys);
                try {
                    //确保表已可用
                    while (true) {
                        if (ZKTableReadOnly.isEnabledTable(master.getZooKeeperWatcher(), tableName))
                            break;
                        Thread.sleep(100);
                    }
                } catch (Exception e) {
                    throw DbException.convert(e);
                }
            }
        } catch (IOException e) {
            throw DbException.convertIOException(e, "Failed to HMaster.createTable");
        }
    }

    private static void dropIfExists(Session session, String tableName) {
        try {
            HMaster master = ((HBaseSession) session).getMaster();
            if (master != null && master.getTableDescriptors().get(tableName) != null) {
                master.disableTable(Bytes.toBytes(tableName));
                while (true) {
                    if (ZKTableReadOnly.isDisabledTable(master.getZooKeeperWatcher(), tableName))
                        break;
                    Thread.sleep(100);
                }
                master.deleteTable(Bytes.toBytes(tableName));
            }
        } catch (Exception e) {
            throw DbException.convert(e); //Failed to HMaster.deleteTable
        }
    }

    private static String toS(ImmutableBytesWritable v) {
        return Bytes.toString(v.get());
    }

    @Override
    public boolean isDistributed() {
        return true;
    }

    @Override
    public boolean supportsColumnFamily() {
        return true;
    }

    @Override
    public boolean supportsAlterColumnWithCopyData() {
        return false;
    }

    @Override
    public boolean supportsIndex() {
        return false;
    }

    @Override
    public long getDiskSpaceUsed() {
        return 0;
    }

    public void addColumn(Column c) {
        ArrayList<Column> list;
        if (c.getColumnFamilyName() == null)
            c.setColumnFamilyName(getDefaultColumnFamilyName());
        list = columnsMap.get(c.getColumnFamilyName());
        if (list == null) {
            list = New.arrayList();
            columnsMap.put(c.getColumnFamilyName(), list);
        }
        list.add(c);

        Column[] cols = getColumns();
        Column[] newCols = new Column[cols.length + 1];
        System.arraycopy(cols, 0, newCols, 0, cols.length);
        newCols[cols.length] = c;

        setColumns(newCols);
    }

    public void dropColumn(Column column) {
        Column[] cols = getColumns();
        ArrayList<Column> list = new ArrayList<Column>(cols.length);
        for (Column c : cols) {
            if (!c.equals(column))
                list.add(c);

        }
        Column[] newCols = new Column[list.size()];
        newCols = list.toArray(newCols);

        rebuildColumnsMap(newCols, getDefaultColumnFamilyName());
    }

    private void rebuildColumnsMap(Column[] cols, String defaultColumnFamilyName) {
        columnsMap = New.hashMap();
        ArrayList<Column> list;
        for (Column c : cols) {
            if (c.getColumnFamilyName() == null)
                c.setColumnFamilyName(defaultColumnFamilyName);
            list = columnsMap.get(c.getColumnFamilyName());
            if (list == null) {
                list = New.arrayList();
                columnsMap.put(c.getColumnFamilyName(), list);
            }
            list.add(c);
        }

        setColumns(cols);
    }

    @Override
    public Row getRow(Session session, long key) {
        return null;
    }
}
