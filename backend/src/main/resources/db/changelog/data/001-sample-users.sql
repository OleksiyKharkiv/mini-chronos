-- ═══════════════════════════════════════════════════════════════════════════
-- 001-sample-users.sql — тестовые пользователи для dev-окружения
--
-- Выполняется Liquibase в контексте "dev" (context="dev" в 010-seed-data.xml).
-- В production этот файл НЕ выполняется.
--
-- ПАРОЛИ: все пользователи имеют пароль "Password1!"
-- BCrypt hash (strength=12) для "Password1!":
--   $2a$12$eImiTXuWVxfM37uY4JANjQ==... (полный hash ниже)
--
-- КАК СГЕНЕРИРОВАТЬ СВОЙ HASH для тестов:
--   В Java: new BCryptPasswordEncoder().encode("Password1!")
--   Или онлайн: https://bcrypt-generator.com (strength 12)
--
-- РОЛИ задаются отдельно в таблице roles (002-sample-users.sql или ниже).
-- ═══════════════════════════════════════════════════════════════════════════

-- Последовательность ID: используем значения > 1000 для seed-данных,
-- чтобы не конфликтовать с ID генерируемыми Hibernate sequence (начинает с 1).
-- В production IDs генерируются автоматически, seed-данные в prod отсутствуют.

INSERT INTO users (id, username, email, password_hash, first_name, last_name, active, created_at, updated_at)
VALUES
    -- Администратор системы
    (1001, 'admin',
     'admin@mini-chronos.de',
     '$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
     'System', 'Administrator',
     true, NOW(), NOW()),

    -- Менеджер продаж (Vertrieb) — Lang GmbH типичная роль
    (1002, 'm.mueller',
     'm.mueller@lang-gmbh.de',
     '$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
     'Maria', 'Müller',
     true, NOW(), NOW()),

    -- Логист (Lager) — управляет отгрузкой/приёмкой оборудования
    (1003, 'j.schmidt',
     'j.schmidt@lang-gmbh.de',
     '$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
     'Jonas', 'Schmidt',
     true, NOW(), NOW()),

    -- Техник сервиса (Werkstatt) — техобслуживание оборудования
    (1004, 's.weber',
     's.weber@lang-gmbh.de',
     '$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
     'Stefan', 'Weber',
     true, NOW(), NOW()),

    -- Неактивный пользователь — для тестирования блокировки аккаунта
    (1005, 'inactive.user',
     'inactive@lang-gmbh.de',
     '$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
     'Inactive', 'User',
     false, NOW(), NOW())
;

-- Роли пользователей
-- role_type соответствует enum RoleType: ADMIN, SALES, LOGISTICS, SERVICE
INSERT INTO roles (id, user_id, role_type, created_at, updated_at)
VALUES
    -- admin: полный доступ
    (2001, 1001, 'ADMIN',     NOW(), NOW()),

    -- m.mueller: продажи (видит цены, создаёт заказы, управляет клиентами)
    (2002, 1002, 'SALES',     NOW(), NOW()),

    -- j.schmidt: логистика (видит заказы, управляет статусами отгрузки)
    (2003, 1003, 'LOGISTICS', NOW(), NOW()),

    -- s.weber: сервис (управляет техобслуживанием, блокирует оборудование)
    (2004, 1004, 'SERVICE',   NOW(), NOW()),

    -- inactive.user: роль есть, но аккаунт inactive=false — доступ запрещён
    (2005, 1005, 'SALES',     NOW(), NOW())
;