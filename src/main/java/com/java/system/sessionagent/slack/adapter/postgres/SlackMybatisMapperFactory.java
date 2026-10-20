package com.java.system.sessionagent.slack.adapter.postgres;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.java.system.sessionagent.storage.adapter.postgres.jdbc.PostgresUuidTypeHandler;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.UUID;

/** Creates Slack mappers for direct PostgreSQL adapter construction in tests. */
final class SlackMybatisMapperFactory {

    private SlackMybatisMapperFactory() {
    }

    static SlackDatabaseMapper create(DataSource dataSource) {
        DataSource requiredDataSource = Objects.requireNonNull(dataSource, "Data source must not be null");
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setCacheEnabled(false);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setReturnInstanceForEmptyRow(true);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.getTypeHandlerRegistry().register(UUID.class, PostgresUuidTypeHandler.class);
        configuration.addMapper(SlackDatabaseMapper.class);

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
            return sqlSessionTemplate.getMapper(SlackDatabaseMapper.class);
        } catch (Exception exception) {
            throw new IllegalStateException("MyBatis SQL session factory could not be initialized", exception);
        }
    }
}
