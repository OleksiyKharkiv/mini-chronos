-- ═══════════════════════════════════════════════════════════════════════════
-- 002-sample-equipment.sql — тестовое оборудование для dev-окружения
--
-- Имитирует реальный инвентарь Lang GmbH (ивент-техника для мероприятий).
-- Типы оборудования соответствуют enum EquipmentType:
--   PROJECTOR, SCREEN, LED_WALL, AUDIO, LIGHTING, OTHER
--
-- Статусы соответствуют enum EquipmentStatus:
--   AVAILABLE, RENTED, MAINTENANCE, RETIRED
--
-- daily_rate: цена аренды за день в EUR (используется как "price snapshot" в OrderItem).
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO equipment_units (id, sku, name, description, type, status, daily_rate, created_at, updated_at)
VALUES

    -- ── PROJECTORS ──────────────────────────────────────────────────────────
    -- Высококлассные лазерные проекторы — основной продукт Lang GmbH
    (3001,
     'PROJ-EPSON-L1755U-001',
     'Epson EB-L1755U #1',
     'Лазерный проектор 15.000 ANSI лм, WUXGA 1920x1200, HDMI/SDI. Идеален для конференций.',
     'PROJECTOR', 'AVAILABLE', 450.00,
     NOW(), NOW()),

    (3002,
     'PROJ-EPSON-L1755U-002',
     'Epson EB-L1755U #2',
     'Лазерный проектор 15.000 ANSI лм, WUXGA 1920x1200, HDMI/SDI.',
     'PROJECTOR', 'AVAILABLE', 450.00,
     NOW(), NOW()),

    (3003,
     'PROJ-EPSON-L1755U-003',
     'Epson EB-L1755U #3',
     'Лазерный проектор 15.000 ANSI лм, WUXGA 1920x1200, HDMI/SDI.',
     'PROJECTOR', 'MAINTENANCE', 450.00,
     NOW(), NOW()),
    -- #3 на техобслуживании — для тестирования что недоступное оборудование
    -- не появляется в результатах поиска (status != AVAILABLE)

    (3004,
     'PROJ-CHRISTIE-WU20K-001',
     'Christie WU20K-J #1',
     'Профессиональный проектор 20.000 лм для крупных залов и стадионов.',
     'PROJECTOR', 'AVAILABLE', 850.00,
     NOW(), NOW()),

    -- ── SCREENS ─────────────────────────────────────────────────────────────
    (3010,
     'SCRN-STUMPFL-4x3-001',
     'Stumpfl 4x3m Leinwand #1',
     'Натяжной экран 4x3 метра, Matt White. Подходит для проекторов до 15k лм.',
     'SCREEN', 'AVAILABLE', 120.00,
     NOW(), NOW()),

    (3011,
     'SCRN-STUMPFL-6x4-001',
     'Stumpfl 6x4m Leinwand #1',
     'Натяжной экран 6x4 метра для больших залов. Требует 2 техника для монтажа.',
     'SCREEN', 'AVAILABLE', 180.00,
     NOW(), NOW()),

    -- ── LED WALLS ────────────────────────────────────────────────────────────
    (3020,
     'LED-ABSEN-P3.9-4x2-001',
     'LED Wall Absen P3.9 (4x2m) #1',
     'LED-видеостена 4x2 метра, Pitch 3.9mm, 1000 nit. Для выставок и сцен.',
     'LED_WALL', 'AVAILABLE', 1200.00,
     NOW(), NOW()),

    (3021,
     'LED-ABSEN-P3.9-4x2-002',
     'LED Wall Absen P3.9 (4x2m) #2',
     'LED-видеостена 4x2 метра, Pitch 3.9mm, 1000 nit.',
     'LED_WALL', 'RENTED', 1200.00,
     NOW(), NOW()),
    -- #2 уже сдана в аренду — для тестирования фильтрации по статусу

    -- ── AUDIO ────────────────────────────────────────────────────────────────
    (3030,
     'AUDIO-LMACOUSTICS-SB28-001',
     'L-Acoustics SB28 Subwoofer #1',
     'Профессиональный сабвуфер 2x18" для живых мероприятий.',
     'AUDIO', 'AVAILABLE', 250.00,
     NOW(), NOW()),

    (3031,
     'AUDIO-YAMAHA-QL5-001',
     'Yamaha QL5 Digitalmixer #1',
     'Цифровой микшер 64 канала, Dante сеть, iPad remote.',
     'AUDIO', 'AVAILABLE', 320.00,
     NOW(), NOW()),

    -- ── LIGHTING ─────────────────────────────────────────────────────────────
    (3040,
     'LIGHT-ROBE-MEGAPOINTE-001',
     'Robe MegaPointe Spot #1',
     'Moving Head Spot/Beam/Wash, 470W LED, IP-защита.',
     'LIGHTING', 'AVAILABLE', 180.00,
     NOW(), NOW()),

    (3041,
     'LIGHT-ROBE-MEGAPOINTE-002',
     'Robe MegaPointe Spot #2',
     'Moving Head Spot/Beam/Wash, 470W LED.',
     'LIGHTING', 'AVAILABLE', 180.00,
     NOW(), NOW()),

    -- Списанное оборудование — для тестирования что RETIRED не показывается
    (3099,
     'PROJ-PANASONIC-PT-DZ570-RETIRED',
     'Panasonic PT-DZ570 (списан)',
     'Устаревший проектор, не используется. Оставлен для истории аренды.',
     'PROJECTOR', 'RETIRED', 0.00,
     NOW(), NOW())
;

-- ── ТЕСТОВЫЕ КЛИЕНТЫ ────────────────────────────────────────────────────────
-- Несколько клиентов для тестирования Customer → Project → Order цепочки

INSERT INTO customers (id, name, email, phone_number, active, created_at, updated_at)
VALUES
    (4001, 'Messe Köln GmbH',
     'technik@messe-koeln.de', '+49 221 821-0',
     true, NOW(), NOW()),

    (4002, 'BMW AG – Werk Köln',
     'events@bmw-koeln.de', '+49 221 9090-0',
     true, NOW(), NOW()),

    (4003, 'Universität zu Köln',
     'veranstaltungen@uni-koeln.de', '+49 221 470-0',
     true, NOW(), NOW()),

    -- Неактивный клиент — для тестирования фильтрации active=false
    (4004, 'Musterfirma GmbH (inaktiv)',
     'info@musterfirma.de', NULL,
     false, NOW(), NOW())
;