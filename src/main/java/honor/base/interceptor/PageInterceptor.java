package honor.base.interceptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.DefaultObjectFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.DefaultObjectWrapperFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import honor.common.Page;
@Intercepts({@Signature(type =StatementHandler.class, method = "prepare", args ={Connection.class})})
public class PageInterceptor implements Interceptor{
	public final static Logger logger = LoggerFactory.getLogger(PageInterceptor.class);
	private static final ObjectFactory DEFAULT_OBJECT_FACTORY = new DefaultObjectFactory();
    private static final ObjectWrapperFactory DEFAULT_OBJECT_WRAPPER_FACTORY = new DefaultObjectWrapperFactory();
	private static  String dialect = null;
	private static  String pageSqlId = null;
	private static  String defaultDialect = "mysql";
	private static  String defaultPageSqlId = ".*Page$";
	public Object intercept(Invocation invocation) throws Throwable {
		
		StatementHandler statementHandler = (StatementHandler) invocation.getTarget();  
	     MetaObject metaStatementHandler = MetaObject.forObject(statementHandler,DEFAULT_OBJECT_FACTORY, DEFAULT_OBJECT_WRAPPER_FACTORY);  
	     // 分离代理对象链(由于目标类可能被多个拦截器拦截，从而形成多次代理，通过下面的两次循环  
	     // 可以分离出最原始的的目标类)  
	     while (metaStatementHandler.hasGetter("h")) {  
	         Object object = metaStatementHandler.getValue("h");  
	         metaStatementHandler = MetaObject.forObject(object, DEFAULT_OBJECT_FACTORY,DEFAULT_OBJECT_WRAPPER_FACTORY);  
	     }  
	     // 分离最后一个代理对象的目标类  
	     while (metaStatementHandler.hasGetter("target")) {  
	         Object object = metaStatementHandler.getValue("target");  
	         metaStatementHandler = MetaObject.forObject(object, DEFAULT_OBJECT_FACTORY,DEFAULT_OBJECT_WRAPPER_FACTORY);  
	     }  
	     Configuration configuration = (Configuration) metaStatementHandler.getValue("delegate.configuration");  
	     dialect = configuration.getVariables().getProperty("dialect");  
	     if (null == dialect || "".equals(dialect)) {  
	         logger.warn("Property dialect is not setted,use default 'mysql' ");  
	         dialect = defaultDialect;  
	     }  
	     pageSqlId = configuration.getVariables().getProperty("pageSqlId");  
	     if (null == pageSqlId || "".equals(pageSqlId)) {  
	         logger.warn("Property pageSqlId is not setted,use default '.*Page$' ");  
	         pageSqlId = defaultPageSqlId;  
	     }  
	     MappedStatement mappedStatement = (MappedStatement)metaStatementHandler.getValue("delegate.mappedStatement");  
	     // 只重写需要分页的sql语句。通过MappedStatement的ID匹配，默认重写以Page结尾的  
	     //  MappedStatement的sql  
	     if (mappedStatement.getId().matches(pageSqlId)) {  
	         BoundSql boundSql = (BoundSql) metaStatementHandler.getValue("delegate.boundSql");  
	         Object parameterObject = boundSql.getParameterObject();  
	         if (parameterObject == null) {  
	             throw new NullPointerException("parameterObject is null!");  
	         } else {  
	             // 分页参数作为参数对象parameterObject的一个属性  
	             Page page = (Page) metaStatementHandler  
	                     .getValue("delegate.boundSql.parameterObject.page");  
	             String sql = boundSql.getSql();  
	             // 重写sql  
	             String pageSql = buildPageSql(sql, page);  
	             metaStatementHandler.setValue("delegate.boundSql.sql", pageSql);  
	             // 采用物理分页后，就不需要mybatis的内存分页了，所以重置下面的两个参数  
	             metaStatementHandler.setValue("delegate.rowBounds.offset",RowBounds.NO_ROW_OFFSET);  
	             metaStatementHandler.setValue("delegate.rowBounds.limit", RowBounds.NO_ROW_LIMIT);  
	             Connection connection = (Connection) invocation.getArgs()[0];  
	             // 重设分页参数里的总页数等  
	             setPageParameter(sql, connection, mappedStatement, boundSql, page);  
	         }  
	     }  
	     // 将执行权交给下一个拦截器  
	     return invocation.proceed();
	}

	public Object plugin(Object target) {
		// 当目标类是StatementHandler类型时，才包装目标类，否者直接返回目标本身,减少目标被代理的  
	    if (target instanceof StatementHandler) {  
	        return Plugin.wrap(target, this);  
	    } else {  
	        return target;  
	    }
	}

	public void setProperties(Properties arg0) {
		// TODO Auto-generated method stub
		
	}
	
	
	private String buildPageSql(String sql, Page page) {  
	    if (page != null) {  
	        StringBuilder pageSql = new StringBuilder();  
	        if ("mysql".equals(dialect)) {  
	            pageSql = buildPageSqlForMysql(sql, page);  
	        } else if ("oracle".equals(dialect)) {  
	            pageSql = buildPageSqlForOracle(sql, page);  
	        } else {  
	            return sql;  
	        }  
	        return pageSql.toString();  
	    } else {  
	        return sql;  
	    }  
	}
	
	
	public StringBuilder buildPageSqlForMysql(String sql, Page page) {  
	    StringBuilder pageSql = new StringBuilder(100);  
	    String beginrow = String.valueOf((page.getCurrentPage() - 1) * page.getPageSize());  
	    pageSql.append(sql);  
	    pageSql.append(" limit " + beginrow + "," + page.getPageSize());  
	    return pageSql;  
	}
	
	
	public StringBuilder buildPageSqlForOracle(String sql, Page page) {  
	    StringBuilder pageSql = new StringBuilder(100);  
	    String beginrow = String.valueOf((page.getCurrentPage() - 1) * page.getPageSize());  
	    String endrow = String.valueOf(page.getCurrentPage() * page.getPageSize());  
	    pageSql.append("select * from ( select temp.*, rownum row_id from ( ");  
	    pageSql.append(sql);  
	    pageSql.append(" ) temp where rownum <= ").append(endrow);  
	    pageSql.append(") where row_id > ").append(beginrow);  
	    return pageSql;  
	}
	
	
	private void setPageParameter(String sql, Connection connection, MappedStatement mappedStatement,BoundSql boundSql, Page page) {  
	    // 记录总记录数  
	    String countSql = "select count(0) from (" + sql + ") as total";  
	    PreparedStatement countStmt = null;  
	    ResultSet rs = null;  
	    try {  
	        countStmt = connection.prepareStatement(countSql);  
	        BoundSql countBS = new BoundSql(mappedStatement.getConfiguration(), countSql,  
	                boundSql.getParameterMappings(), boundSql.getParameterObject());  
	        setParameters(countStmt, mappedStatement, countBS, boundSql.getParameterObject());  
	        rs = countStmt.executeQuery();  
	        int totalCount = 0;  
	        if (rs.next()) {  
	            totalCount = rs.getInt(1);  
	        }  
	        page.setTotalRows(totalCount);  
	        int totalPage = totalCount / page.getPageSize() + ((totalCount % page.getPageSize() == 0) ? 0 : 1);  
	        page.setTotalPages(totalPage);  
	    } catch (SQLException e) {  
	        logger.error("Ignore this exception", e);  
	    } finally {  
	        try {  
	            rs.close();  
	        } catch (SQLException e) {  
	            logger.error("Ignore this exception", e);  
	        }  
	        try {  
	            countStmt.close();  
	        } catch (SQLException e) {  
	            logger.error("Ignore this exception", e);  
	        }  
	    }  
	}  
	  
	/** 
	 * 对SQL参数(?)设值 
	 *  
	 * @param ps 
	 * @param mappedStatement 
	 * @param boundSql 
	 * @param parameterObject 
	 * @throws SQLException 
	 */  
	private void setParameters(PreparedStatement ps, MappedStatement mappedStatement, BoundSql boundSql,  
	        Object parameterObject) throws SQLException {  
	    ParameterHandler parameterHandler = new DefaultParameterHandler(mappedStatement, parameterObject, boundSql);  
	    parameterHandler.setParameters(ps);  
	}  

}
