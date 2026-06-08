package com.softropic.skillars.utils.sql;

import org.assertj.core.api.Assertions;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceUnitUtil;


public class EntityFetchAsserter {
    private PersistenceUnitUtil puUtil;

    public EntityFetchAsserter(EntityManagerFactory emf) {
        this.puUtil =  emf.getPersistenceUnitUtil();
    }

    public <T> FetchType assertThat(T entity){
        return new FetchType(entity);
    }

    public class FetchType<T> {
        private  final T  loadedEntity;

        FetchType(T entity) {
            this.loadedEntity = entity;
        }

        public FetchType isLazyLoaded(String attribute) {
            //TODO PersistenceUnitUtil#isLoaded is not always accurate; consider using EntityManager#contains instead
            // Also see Hibernate.isInitialized(proxy)
            //TODO this is the jpa impl. Persistence.getPersistenceUtil().isLoaded(loadedEntity, attribute);
            Assertions.assertThat(puUtil.isLoaded(loadedEntity, attribute)).isFalse();
            return this;
        }

        public FetchType isEagerlyLoaded(String attribute) {
            //TODO PersistenceUnitUtil#isLoaded is not always accurate; consider using EntityManager#contains instead
            // Also see Hibernate.isInitialized(proxy), Hibernate.isPropertyInitialized.
            // PersistenceUnitUtil#isLoaded actually uses hibernate under the hood
            Assertions.assertThat(puUtil.isLoaded(loadedEntity, attribute)).isTrue();
            return this;
        }
    }
}
