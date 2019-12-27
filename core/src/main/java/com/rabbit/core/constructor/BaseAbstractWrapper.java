package com.rabbit.core.constructor;

import cn.hutool.json.JSONObject;
import com.rabbit.core.annotation.Column;
import com.rabbit.core.annotation.Id;
import com.rabbit.core.annotation.Table;
import com.rabbit.core.bean.TableFieldInfo;
import com.rabbit.core.bean.TableInfo;
import com.rabbit.core.enumation.MySqlColumnType;
import com.rabbit.exception.MyBatisRabbitPlugException;
import com.rabbit.utils.ClassUtils;
import com.rabbit.utils.CollectionUtils;
import com.rabbit.utils.StringUtils;
import com.sun.org.apache.xpath.internal.operations.Lt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 抽象基类包装器
 *
 * @param <E>
 * @author duxiaoyu
 * @since 2019-12-12
 */
public abstract class BaseAbstractWrapper<E> implements Serializable {

    private static final Logger logger = LoggerFactory.getLogger(BaseAbstractWrapper.class);

    // Mybatis-Rabbit-Plug-TAG
    private static final String TAG = "Mybatis-Rabbit-Plug";

    /**
     * class.bean
     */
    private E clazz;

    /**
     * BaseAbstractWrapper-constructor
     *
     * @param clazz
     */
    public BaseAbstractWrapper(E clazz) {
        this.clazz = clazz;
    }

    public BaseAbstractWrapper() {
    }

    /**
     * 缓存 TableInfo 数据，作为全局缓存
     */
    private static final Map<Class<?>, TableInfo> TABLE_INFO_CACHE = new ConcurrentHashMap<>();


    /***************************************** TableInfo 缓存 ***********************************************/
    /**
     * 添加 TableInfo 缓存
     *
     * @param clazz     bean.class
     * @param tableInfo After initialization tableInfo
     */
    public void addTableInfoCache(Class<?> clazz, TableInfo tableInfo) {
        if (clazz != null && !Objects.isNull(tableInfo)) {
            TABLE_INFO_CACHE.put(ClassUtils.getUserClass(clazz), tableInfo);
        }
    }

    /**
     * 获取缓存的 TableInfo
     *
     * @param clazz bean.class
     * @return TableInfo
     */
    public TableInfo getTableInfo(Class<?> clazz) {
        return TABLE_INFO_CACHE.get(ClassUtils.getUserClass(clazz));
    }

    /**
     * 获取所有缓存的TableInfo
     *
     * @return Map<Class  <?> , TableInfo>
     */
    public Map<Class<?>, TableInfo> getAllTableInfo() {
        return TABLE_INFO_CACHE;
    }

    /**
     * 删除 TableInfo 缓存
     *
     * @param clazz bean.class
     */
    public void removeTableInfoCache(Class<?> clazz) {
        TABLE_INFO_CACHE.remove(ClassUtils.getUserClass(clazz));
    }

    /**
     * 清空 所有TableInfo 缓存
     */
    public void clearTableInfoCache() {
        TABLE_INFO_CACHE.clear();
    }

    /***************************************** TableInfo 缓存 ***********************************************/


    /**
     * 解析 bean.Class
     * 此处解析会进行全局缓存处理
     *
     * @return TableInfo
     * @author duxiaoyu
     */
    public TableInfo analysisClazz() {
        TableInfo tableInfo = null;
        String tableName = "";
        Map<String, TableFieldInfo> tbFieldMap = new ConcurrentHashMap<>();
        if (!Objects.isNull(clazz)) {
            logger.info("{}:开始解析数据库表信息>>>>>>", TAG);
            tableInfo = new TableInfo();
            // 判断是否使用@Table
            if (clazz.getClass().isAnnotationPresent(Table.class)) {
                Table table = clazz.getClass().getAnnotation(Table.class);
                tableName = table.value();
            } else {
                // 自动解析对应的数据库表名称，默认按照驼峰转下划线格式进行转换，如: GoodsInfo -> goods_info
                String className = clazz.getClass().getSimpleName();
                tableName = StringUtils.camelToUnderline(StringUtils.firstToLowerCase(className));
            }
            logger.info("{}:解析出的数据库表名称:{}", TAG, tableName);
            // 开始解析entity中的field
            List<Field> fieldList = Arrays.asList(clazz.getClass().getDeclaredFields());
            if (!CollectionUtils.isEmpty(fieldList)) {
                logger.info("{}:开始解析数据库表字段信息>>>>>>", TAG);
                for (Field item : fieldList) {
                    if (item.isAnnotationPresent(Column.class)) {
                        Column column = item.getAnnotation(Column.class);
                        // 判断是否是数据库表字段，如果不是，直接忽略不进行解析
                        if (!column.isTableColumn()) {
                            continue;
                        }
                    }
                    TableFieldInfo tbField = new TableFieldInfo();
                    String columnName = "";// 数据库表字段名
                    String propertyName = "";// bean属性名
                    // 判断是否使用@Column 或 @Id
                    if (item.isAnnotationPresent(Column.class) || item.isAnnotationPresent(Id.class)) {
                        Column column = item.getAnnotation(Column.class);
                        Id id = item.getAnnotation(Id.class);
                        if (!Objects.isNull(column)) {
                            columnName = column.value();
                        } else if (!Objects.isNull(id)) {
                            columnName = id.value();
                            // 设置table指定的主键字段
                            tableInfo.setPrimaryKey(item);
                        }
                        // 判断使用@column是否设置了对应的数据库表字段名称，如果没有设置，自动解析字段，默认按照驼峰转下划线格式进行转换，如: salePrice -> sale_price
                        if (org.apache.commons.lang3.StringUtils.isBlank(columnName)) {
                            propertyName = item.getName();
                            columnName = StringUtils.camelToUnderline(propertyName);
                        }
                        // 获取字段对应的数据类型: 如果设置了对应的数据类型，就直接获取，如果没有就自动获取默认数据类型
                        if (!Objects.isNull(column)) {
                            // 此处在设置数据库字段类型时，如果没有指定，会拿默认的数据库类型varchar，
                            // 但是这个情况是不符合实际情况的，如果是枚举类型，其实对应的应该不是varchar，此处问题不是很大，所以暂时先不做处理
                            tbField.setColumnType(column.columnType());
                        }
                        if(!Objects.isNull(id)){
                            tbField.setColumnType(id.columnType());
                        }
                    } else {
                        // 自动解析字段，默认按照驼峰转下划线格式进行转换，如: salePrice -> sale_price
                        propertyName = item.getName();
                        columnName = StringUtils.camelToUnderline(propertyName);
                        tbField.setColumnType(this.getColumnType(item.getGenericType()));
                    }
                    tbField.setField(item);
                    tbField.setColumnName(columnName);
                    tbField.setPropertyName(propertyName);
                    Class<?> clazzFieldType = item.getType();
                    tbField.setPropertyType(clazzFieldType);
                    tbFieldMap.put(propertyName, tbField);
                }
                logger.info("{}:解析数据库表字段信息完成>>>>>>", TAG);
            } else {
                throw new MyBatisRabbitPlugException("解析Class-Field异常，未能获取到Field......");
            }
            tableInfo.setId(1);// 表默认主键，暂时没有用到的地方
            tableInfo.setTableName(tableName);
            tableInfo.setColumnMap(tbFieldMap);
        } else {
            throw new MyBatisRabbitPlugException("解析bean.Class异常，Class为空......");
        }

        if (Objects.isNull(tableInfo)) {
            throw new MyBatisRabbitPlugException("解析bean.Class异常，TableInfo为空，缓存失败......");
        }

        // 缓存 TableInfo
        TABLE_INFO_CACHE.put(ClassUtils.getUserClass(clazz), tableInfo);
        return tableInfo;
    }

    /**
     * 获取数据库中字段的数据类型
     *
     * @param type 类型
     * @return MySqlColumnType
     */
    public MySqlColumnType getColumnType(Type type) {
        if (type instanceof Class<?>) {
            // 判断具体的数据类型
            Class<?> clazz = (Class<?>) type;
            if (Integer.class.isAssignableFrom(clazz)) {
                return MySqlColumnType.INT;
            } else if (String.class.isAssignableFrom(clazz)) {
                return MySqlColumnType.VARCHAR;
            } else if (Double.class.isAssignableFrom(clazz)) {
                return MySqlColumnType.DOUBLE;
            } else if (Float.class.isAssignableFrom(clazz)) {
                return MySqlColumnType.FLOAT;
            } else if (BigDecimal.class.isAssignableFrom(clazz)) {
                return MySqlColumnType.DECIMAL;
            } else if (Date.class.isAssignableFrom(clazz)) {
                return MySqlColumnType.DATE;
            } else if (Long.class.isAssignableFrom(clazz)) {
                return MySqlColumnType.BIGINT;
            } else if (Boolean.class.isAssignableFrom(clazz)) {
                return MySqlColumnType.TINYINT;
            } else if (Short.class.isAssignableFrom(clazz)) {
                return MySqlColumnType.SHORT;
            } else if (Character.class.isAssignableFrom(clazz)) {
                return MySqlColumnType.CHAR;
            }
        }

        if (clazz.getClass().isArray()) {
            throw new MyBatisRabbitPlugException("属性类型为数据或集合，无法获取对应的数据类型......");
        } else if (clazz.getClass().isEnum()) {
            // 枚举在数据库中对应TINYINT类型
            return MySqlColumnType.TINYINT;
        } else {
            throw new MyBatisRabbitPlugException("属性类型为未知类型，可能是object或自定义bean，无法获取对应的数据类型......");
        }
    }


    //TODO 待实现 ...

}