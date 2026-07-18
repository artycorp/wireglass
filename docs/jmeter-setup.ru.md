# Подключение Wireglass к обычному JMeter (`.jmx`, без DSL)

Инструкция для стокового JMeter — GUI или консольного `-n`. Для jmeter-java-dsl нужен другой
модуль, `wireglass-client`; см. корневой [README](../README.md).

Работает это так: в план добавляется **Backend Listener** с нашей реализацией
`WireglassBackendListener`. Она отдаёт каждый сэмпл в общий конвейер захвата и шлёт его JSON-ом
по WebSocket на `/api/ingest` работающего приложения. Ниже по течению код тот же, что у
DSL-клиента, поэтому `.jmx`-прогон выглядит в браузере ровно как запуск из формы.

Проверено на JMeter 5.6.3 (Homebrew) + Java 17.

---

## 1. Собрать плагин

Из корня репозитория:

```bash
mvn -pl wireglass-jmeter -am package
```

Нужен jar с классификатором `-jmeter` — он самодостаточный (наш код + Java-WebSocket +
Jackson, перепакованный в `com.wireglass.listview.jmeter.shaded.jackson`, чтобы не конфликтовать
с Jackson самого JMeter):

```
wireglass-jmeter/target/wireglass-jmeter-0.0.1-SNAPSHOT-jmeter.jar
```

Тонкий `wireglass-jmeter-0.0.1-SNAPSHOT.jar` (без суффикса) в `lib/ext` класть **не надо** —
он без зависимостей и упадёт с `NoClassDefFoundError`.

## 2. Найти JMETER_HOME

Это каталог, внутри которого лежат `bin/` и `lib/ext/`. Для Homebrew на macOS:

```bash
export JMETER_HOME=/opt/homebrew/opt/jmeter/libexec
```

Проверка — команда должна показать список `ApacheJMeter_*.jar`:

```bash
ls "$JMETER_HOME/lib/ext/"
```

## 3. Удалить старый плагин, если он есть

До переименования проекта плагин назывался `web-listview-jmeter-*.jar` и содержал класс
`com.artembelikov.listview.jmeter.WireglassBackendListener`. Имена классов у старой и новой
версии **разные**, поэтому JMeter спокойно загрузит оба и ошибки не покажет — просто старые
планы будут молча стримить через старый класс.

```bash
ls "$JMETER_HOME/lib/ext/" | grep -i 'web-listview\|wireglass'
rm -f "$JMETER_HOME/lib/ext/web-listview-jmeter-"*.jar
```

Если в существующих `.jmx` прописан старый FQCN — заменить на новый (см. шаг 5), иначе после
удаления jar план упадёт на `ClassNotFoundException`.

## 4. Установить плагин

```bash
cp wireglass-jmeter/target/wireglass-jmeter-*-jmeter.jar "$JMETER_HOME/lib/ext/"
```

JMeter сканирует `lib/ext` только при старте — **работающий GUI надо перезапустить**.

## 5. Добавить Backend Listener в план

**В GUI:** правый клик на Test Plan (или Thread Group) → *Add* → *Listener* → *Backend Listener*.
В поле **Backend Listener implementation** выбрать из выпадающего списка:

```
com.wireglass.listview.jmeter.WireglassBackendListener
```

Если класса в списке нет — jar не подхватился: проверьте шаг 4 и перезапуск JMeter.

Параметры подставятся сами, значения по умолчанию рабочие:

| Параметр       | По умолчанию            | Смысл                                                        |
|----------------|-------------------------|--------------------------------------------------------------|
| `serverUrl`    | `http://localhost:8080` | Адрес запущенного Wireglass.                                  |
| `maxBodyBytes` | `262144`                | Обрезка тела пакета, байт.                                    |
| `runId`        | *(пусто)*               | UUID, чтобы сгруппировать сэмплы в один run; пусто = авто.    |

**Правкой XML** — тот же элемент внутри `<hashTree>` тест-плана:

```xml
<BackendListener guiclass="BackendListenerGui" testclass="BackendListener" testname="Wireglass" enabled="true">
  <elementProp name="arguments" elementType="Arguments" guiclass="ArgumentsPanel" testclass="Arguments">
    <collectionProp name="Arguments.arguments">
      <elementProp name="serverUrl" elementType="Argument">
        <stringProp name="Argument.name">serverUrl</stringProp>
        <stringProp name="Argument.value">http://localhost:8080</stringProp>
        <stringProp name="Argument.metadata">=</stringProp>
      </elementProp>
    </collectionProp>
  </elementProp>
  <stringProp name="classname">com.wireglass.listview.jmeter.WireglassBackendListener</stringProp>
</BackendListener>
```

Готовый план: [`wireglass-jmeter/examples/wireglass-example.jmx`](../wireglass-jmeter/examples/wireglass-example.jmx).

## 6. Запустить Wireglass

```bash
./wireglass-app/run.sh
```

Скрипт собирает модули и стартует приложение на <http://localhost:8080> с «плоским»
classpath — это обязательно, потому что движок JMeter резолвит путь к jar каждого тест-элемента
через `new File(codeSource.toURI())`, а внутри Spring Boot fat jar такой путь не строится
(`URI is not hierarchical`). По той же причине не работает `java -jar`.

## 7. Прогнать план

```bash
"$JMETER_HOME/bin/jmeter" -n -t путь/к/плану.jmx
```

Пакеты появляются в браузере в реальном времени. Захваченные до открытия страницы — подтянутся
при загрузке. Тела HTTPS видны расшифрованными: TLS терминирует сам JMeter как клиент,
никакой MITM не нужен.

Проверить без браузера:

```bash
curl -s 'http://localhost:8080/api/packets?limit=10' | python3 -m json.tool | head -40
```

Если в JMeter прошло 6 сэмплов — в ответе должно быть 6 пакетов с `method`, `url`, `status`,
`requestHeaders`, `responseBody` и общим `runId`.

---

## Если не работает

**В списке реализаций нет нашего класса.** Jar не в `lib/ext`, положен тонкий jar вместо
`-jmeter`, или GUI не перезапускали после копирования.

**План стартует, но пакетов ноль.** Скорее всего Wireglass не поднят или `serverUrl` смотрит не
туда. В логе JMeter при старте должна быть строка:

```
Wireglass backend listener streaming to http://localhost:8080
```

Нет строки — `setupTest` не вызывался, то есть слушатель в плане не активен (проверьте галочку
`enabled`). Есть строка, а пакетов нет — смотрите, отвечает ли `curl http://localhost:8080/api/packets`.

**`Port 8080 was already in use`.** Часто это забытый инстанс Wireglass с прошлого раза —
он может быть собран из старого кода и вводить в заблуждение. Найти и погасить:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
kill <PID>
```

**Тест просел по throughput под нагрузкой.** Backend Listener складывает сэмплы в очередь
(по умолчанию 5000, `BackendListener.DEFAULT_QUEUE_SIZE`). Сэмплы при переполнении **не
теряются** — JMeter блокирует поток на `queue.put()`, то есть замедляется сам тест. В конце
прогона это видно в логе:

```
QueueWaits: <N>; QueueWaitTime: <нс>, you may need to increase queue capacity,
see property 'backend_queue_capacity'
```

Появилась такая строка — поднимите *Async Queue size* в GUI элемента (или свойство
`backend_queue_capacity`) либо уменьшите `maxBodyBytes`, чтобы на пакет уходило меньше работы.

**`NoSuchMethodError` вокруг Jackson.** Признак того, что в `lib/ext` попал не тот jar:
в правильном Jackson перепакован под `com.wireglass.listview.jmeter.shaded.jackson`.
Проверить содержимое:

```bash
unzip -l "$JMETER_HOME/lib/ext/wireglass-jmeter-"*-jmeter.jar | grep -c 'shaded/jackson'
```
