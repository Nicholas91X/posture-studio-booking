-- V6__create_spring_session.sql
-- The sessions of the signed-in users (guide 25, part 4), kept by Spring Session JDBC so that they survive
-- a restart of the application: with sessions in Tomcat's memory, every new version would sign everybody out.
-- Copied from org/springframework/session/jdbc/schema-postgresql.sql of Spring Session 4.1.1: the tables
-- belong to Spring Session, so names and columns stay its own. Spring is told not to create them
-- (spring.session.jdbc.initialize-schema=never): the schema changes only through Flyway.
-- Times are milliseconds since 1970 (bigint), as Spring Session writes them.

create table spring_session (
    primary_id              char(36)        not null,
    session_id              char(36)        not null,
    creation_time           bigint          not null,
    last_access_time        bigint          not null,
    max_inactive_interval   int             not null,
    expiry_time             bigint          not null,
    principal_name          varchar(100),

    constraint spring_session_pk primary key (primary_id)
);

create unique index spring_session_ix1 on spring_session (session_id);
-- the cleanup of Spring Session (every minute) deletes by expiry
create index spring_session_ix2 on spring_session (expiry_time);
create index spring_session_ix3 on spring_session (principal_name);

-- the attributes of a session (for us: the user id and the instant of the sign-in), Java-serialized
create table spring_session_attributes (
    session_primary_id      char(36)        not null,
    attribute_name          varchar(200)    not null,
    attribute_bytes         bytea           not null,

    constraint spring_session_attributes_pk primary key (session_primary_id, attribute_name),
    constraint spring_session_attributes_fk foreign key (session_primary_id)
        references spring_session (primary_id) on delete cascade
);
