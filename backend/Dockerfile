# ─────────────────────────────────────────────────────────────────────────────
# Dockerfile — multi-stage build for mini-chronos backend
#
# MULTI-STAGE BUILD: why two FROM instructions?
#
# Stage 1 (builder): компилируем и собираем JAR
#   - Нужен JDK (Java Development Kit) с Maven
#   - Образ большой (~500MB) — нужен только для сборки
#
# Stage 2 (runtime): только запускаем JAR
#   - Нужен только JRE (Java Runtime Environment)
#   - Образ маленький (~200MB) — именно он деплоится
#
# Без multi-stage: финальный образ содержал бы Maven, JDK, исходники,
# временные файлы сборки — всё это лишний вес и поверхность атаки.
# С multi-stage: в продакшн уходит только JRE + JAR. Чисто и безопасно.
# ─────────────────────────────────────────────────────────────────────────────

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

# Рабочая директория внутри контейнера
WORKDIR /app

# Копируем сначала только pom.xml — хитрость для кэширования слоёв Docker.
#
# КАК РАБОТАЕТ КЭШИРОВАНИЕ СЛОЁВ:
# Docker строит образ слой за слоем. Каждая инструкция = новый слой.
# Если слой не изменился → Docker берёт его из кэша (не пересобирает).
#
# Если скопировать всё сразу (COPY . .), то при изменении ЛЮБОГО файла
# Docker пересобирает всё включая "mvn dependency:go-offline".
# Загрузка всех зависимостей Maven занимает 2-5 минут.
#
# Правильный порядок:
#   1. COPY pom.xml → редко меняется
#   2. RUN mvn dependency:go-offline → скачивает зависимости (кэшируется!)
#   3. COPY src → меняется часто
#   4. RUN mvn package → пересобирается только при изменении src
#
# Итог: при изменении Java-кода пересобирается только шаг 4 (~10 секунд).
# Без этой оптимизации: каждый раз 5+ минут на скачивание зависимостей.
COPY pom.xml .
COPY .mvn/ .mvn/
COPY mvnw .

# Скачиваем все зависимости заранее (этот слой кэшируется)
RUN ./mvnw dependency:go-offline -B

# Теперь копируем исходники (этот слой обновляется при каждом изменении кода)
COPY src ./src

# Собираем JAR, пропуская тесты (тесты гоняем отдельно в CI)
# -B: batch mode (без интерактивных подсказок Maven)
# -DskipTests: не запускать тесты при сборке образа
RUN ./mvnw clean package -B -DskipTests

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
# eclipse-temurin:21-jre-alpine — минимальный JRE на Alpine Linux
# Alpine: ~5MB base OS (vs ~70MB для Ubuntu)
# JRE (не JDK): только runtime, без компилятора и инструментов разработки
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Создаём непривилегированного пользователя для запуска приложения.
# ПОЧЕМУ НЕ root?
# Если злоумышленник найдёт RCE-уязвимость в приложении,
# он получит доступ только от имени "chronos" (ограниченные права),
# а не от имени root (полный контроль над системой).
# Принцип минимальных привилегий — стандарт для production-контейнеров.
RUN addgroup -S chronos && adduser -S chronos -G chronos

# Копируем JAR из builder-стадии.
# target/*.jar — имя файла включает версию (mini-chronos-0.0.1-SNAPSHOT.jar).
# Переименовываем в app.jar — стабильное имя, не зависящее от версии.
COPY --from=builder /app/target/*.jar app.jar

# Устанавливаем владельца файла
RUN chown chronos:chronos app.jar

# Переключаемся на непривилегированного пользователя
USER chronos

# Документируем порт (не публикует — это делает docker-compose)
EXPOSE 8080

# JVM flags для контейнерной среды:
# -XX:+UseContainerSupport: JVM читает лимиты памяти контейнера,
#   а не физической машины. Без этого JVM может выделить 4GB heap
#   в контейнере с лимитом 512MB → OOM kill.
# -XX:MaxRAMPercentage=75.0: используем 75% доступной памяти контейнера.
#   Оставляем 25% для OS, метаданных JVM, стека потоков.
# -Djava.security.egd=file:/dev/./urandom: ускоряет генерацию случайных чисел.
#   /dev/random блокируется если энтропии мало (типично в контейнерах).
#   /dev/urandom не блокируется — достаточно безопасен для нашего случая.
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]