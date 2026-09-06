package com.java.system.sessionagent.storage.adapter.postgres;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.UUID;

/**
 * Creates the mapper used by direct adapter construction in PostgreSQL tests.
 * Spring production wiring uses the Boot-managed SqlSessionFactory instead.
 */
final class PostgresMybatisMapperFactory {

    private PostgresMybatisMapperFactory() {
    }

    static PostgresConversationMapper create(DataSource dataSource) {
        DataSource requiredDataSource = Objects.requireNonNull(dataSource, "Data source must not be null");
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setCacheEnabled(false);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setReturnInstanceForEmptyRow(true);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.getTypeHandlerRegistry().register(UUID.class, PostgresUuidTypeHandler.class);
        configuration.addMapper(PostgresConversationMapper.class);

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(requiredDataSource);
        factoryBean.setConfiguration(configuration);
        try {
            factoryBean.afterPropertiesSet();
            SqlSessionFactory sqlSessionFactory = Objects.requireNonNull(factoryBean.getObject(),
                    "MyBatis SQL session factory must not be null");
            // The mapper retains this template and manages per-call sessions; manually closing it throws UnsupportedOperationException.
            @SuppressWarnings("resource")
            SqlSessionTemplate sqlSessionTemplate = new SqlSessionTemplate(sqlSessionFactory);
            return sqlSessionTemplate.getMapper(PostgresConversationMapper.class);
        } catch (Exception exception) {
            throw new IllegalStateException("MyBatis SQL session factory could not be initialized", exception);
        }
    }
}
