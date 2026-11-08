# Проверка лент 8 ноября 2026

Пробы шли по HTTPS, без ключа и без обхода проверки. Клиент пробы: `SvodkaLocalFeedProbe/1.0`. Сборщик: `SvodkaLocalCollector/1.0`. Скачивался только XML ленты, не HTML статей. Время проб — около 09:00–09:30 UTC, то есть день 8 ноября в `Asia/Dushanbe`.

## Подключённые адреса

Все адреса ниже в этот день отдали RSS или Atom и HTTP 200, кроме отдельно помеченного DARKER.

| id | URL | Что увидели |
| --- | --- | --- |
| habr-ai | `https://habr.com/ru/rss/hubs/artificial_intelligence/articles/?fl=ru` | 200, text/xml, свежая запись `Sun, 08 Nov 2026 08:59:47 GMT`. Copyright нет. |
| habr-ml | `https://habr.com/ru/rss/hubs/machine_learning/articles/?fl=ru` | 200, `08:59:49 GMT`. Copyright нет. |
| habr-infosec | `https://habr.com/ru/rss/hubs/infosecurity/articles/?fl=ru` | 200, `08:59:50 GMT`. |
| habr-android | `https://habr.com/ru/rss/hubs/android_dev/articles/?fl=ru` | 200, `08:59:51 GMT`. |
| habr-gadgets | `https://habr.com/ru/rss/hubs/gadgets/articles/?fl=ru` | 200, `08:59:52 GMT`. |
| habr-hardware | `https://habr.com/ru/rss/hubs/hardware/articles/?fl=ru` | 200, `08:59:53 GMT`. |
| habr-astronomy | `https://habr.com/ru/rss/hubs/astronomy/articles/?fl=ru` | 200, `08:59:54 GMT`. |
| habr-gamedev | `https://habr.com/ru/rss/hubs/gamedev/articles/?fl=ru` | 200, `08:59:55 GMT`. |
| habr-popsci | `https://habr.com/ru/rss/hubs/popular_science/articles/?fl=ru` | 200, `08:59:56 GMT`. Без слова темы пункт не проходит. |
| habr-develop | `https://habr.com/ru/rss/flows/develop/articles/?fl=ru` | 200, `08:59:57 GMT`. |
| 3dnews | `https://3dnews.ru/news/rss/` | 200, `Sun, 08 Nov 2026 11:58:59 +0300`. |
| 3dnews-games | `https://3dnews.ru/games/rss/` | 200, `11:55:41 +0300`. |
| 3dnews-soft | `https://3dnews.ru/software/rss/` | 200, канал обновлён `12:25 +0300`. Файл 3 428 252 байта и 960 item. Первый item в выборке был от 8 октября 2026. Хвост XML невалиден (`invalid token` около строки 3962). Сборщик берёт первые 80 записей и не падает. |
| ixbt | `https://www.ixbt.com/export/news/rss.xml` | 200, `11:42:00 +0300`. Короткого description нет, текст берётся из content и режется. |
| ixbt-hard | `https://www.ixbt.com/export/hardnews.rss` | 200, редирект на rss с категориями железа, та же минута. |
| opennet | `https://www.opennet.ru/opennews/opennews_all_utf.rss` | 200, UTF-8, `10:02:45 +0300`. Вариант без `_utf` отдал битую кириллицу и не используется. |
| securitylab | `https://www.securitylab.ru/_services/export/rss/` | 200, `10:27:39 +0300`, description около 60–130 знаков. |
| xakep | `https://xakep.ru/feed/` | 200. Свежая запись в пробе: `Fri, 06 Nov 2026 18:30:45 +0000`. |
| stopgame | `https://rss.stopgame.ru/rss_news.xml` | 200, `12:00:08 +0300`. `stopgame.ru/rss/rss_news.xml` перенаправил сюда. `rss_articles.xml` — 404. |
| kanobu-pulse | `https://www.igromania.ru/rss/news.xml` | 200, заголовок канала «Kanobu.ru: пульс», `11:31:00 +0300`. Имя взято из ленты, не из домена. |
| cubiq | `https://cubiq.ru/feed/` | 200, `Sun, 08 Nov 2026 08:25:07 +0000`. |
| 4pda | `https://4pda.to/feed/` | 200, windows-1251, `07:45:00 +0000`. `4pda.ru` не резолвился. |
| overclockers | `https://overclockers.ru/rss/news.rss` | 200. Свежая запись: `Fri, 06 Nov 2026 22:45:33 +0300`. `www.overclockers.ru/rss.xml` — 404. |
| androidinsider | `https://androidinsider.ru/feed` | 200. Свежая запись: `Sat, 07 Nov 2026 16:00:35 +0000`. Description короткий, content:encoded длинный; берётся description. |
| nplus1 | `https://nplus1.ru/rss` | 200. Свежая запись: `Fri, 06 Nov 2026 18:56:34 +0300`. `nplus1.ru/rss/news` и тематические URL отвечали 403. |
| elementy | `https://elementy.ru/rss/news` | 200. Свежая запись: `05 Nov 2026 04:26:00 +0300`, чаще вне окна. |
| postnauka | `https://postnauka.org/feed` | 200, редирект с `postnauka.ru/feed`. Свежая запись: `Wed, 04 Nov 2026 14:11:00 +0000`. |
| mirf | `https://www.mirf.ru/feed/` | 200, Atom, `2026-11-08T10:00:33+03:00`. Жанровое слово обязательно. |
| darker | `https://darkermagazine.ru/feed` | Сначала 200, copyright `Copyright 2026, https://darkermagazine.ru/`, запись `Sat, 07 Nov 2026 09:23:45 +0000`. Сборщик затем получил HTTP 403 три раза. В списке источник выключен. |
| intermedia | `https://www.intermedia.ru/rss/news.xml` | 200, `11:27:00 +0300`. Альбом проходит, концерт нет. |
| newsmuz | `https://newsmuz.com/rss` | 200. Свежая запись: `Fri, 06 Nov 2026 13:52:17 +0000`. Description бывает на тысячи знаков, предел 360. |
| bbc-russian | `https://feeds.bbci.co.uk/russian/rss.xml` | 200, `Sun, 08 Nov 2026 08:48:54 GMT`. |
| meduza-news | `https://meduza.io/rss/news` | 200, `11:12:12 +0300`. |
| kommersant-world | `https://www.kommersant.ru/rss/section-world.xml` | 200, copyright `1991-2026, АО «Коммерсантъ».`, `11:44:23 +0300`. `/RSS/` редиректит на `/rss/`. Один запрос в середине проб вернул 406, соседние — 200. Предел фрагмента 280. |
| interfax | `https://www.interfax.ru/rss` | 200, редирект с `/rss.asp`, `11:54:00 +0300`. |

## Живой выпуск

Каталог `/tmp/svodka-final`, в репозиторий не коммитился.

- `edition=2026-11-08`, `generatedAt=2026-11-08T09:29:10.430142444Z`, `partial=false`, 20 пунктов, файл около 56 КБ.
- Счётчики: seen 1304, outOfWindow 846, lowConfidence 356, excluded 26, duplicates 8, notRussian 6, badDate 2, published 20.
- Темы выпуска: programming 4, ai 4, gadgets 4, hardware 4, games 3, music 1.
- Повтор сразу после этого: `skipped=true`, `published=20`, код процесса 0.
- Более ранний прогон с лимитом 1,5 МБ пометил `3dnews-soft` как «ответ больше лимита» и DARKER как 403, выпуск был `partial=true`. Лимит тела поднят до 4 МБ, разбор останавливается после 80 записей, DARKER выключен. Итоговый прогон выше уже с этим кодом.

## Адреса, которые не вошли

Проверены и не используются, потому что не дали пригодную ленту или не подходят по правилу:

- `3dnews.ru/hardware/rss/` — 404.
- `ixbt.com/export/mobilenews.rss` — 404.
- `opennet.ru/opennews/opennews_all.rss` — не UTF-8, кириллица битая.
- `securitylab.ru/rss/all.xml` — 404.
- `dtf.ru/rss` и `/rss/all` — 200, но заголовок канала «Популярное за все время», это не дневная лента. `dtf.ru/anime/rss`, `/games/rss`, `/music/rss`, `/rss/new` — 404.
- `stopgame.ru/rss/rss_articles.xml` — 404.
- `playground.ru/rss/news.xml` — таймаут чтения.
- `4pda.ru/feed/` — имя не резолвится. `4pda.to/page/1/rss/` — 403.
- `ferra.ru/exports/rss.xml` — таймаут.
- `the-flow.ru/feed`, `/rss`, `/rss.xml`, `/news/rss` — 404.
- `world-art.ru` — таймаут TLS.
- `shikimori.one/rss` — 404.
- `animego.org/rss.xml`, `animego.me/feed` — 404.
- `anilibria.top/public/api/rss`, `anilibria.tv/public/rss.php`, `anilibria.tv/public/rss.xml` — 404.
- `animage.ru` — нет такого имени. `animedia.tv/rss.xml` — цикл редиректов.
- `fantlab.ru/rss`, `/news/rss` — 404.
- `intermedia.ru/news/rss` — 404. `intermedia.ru/rss/music.xml` не проверялся отдельно: рабочий адрес — `/rss/news.xml`.
- `newsmuz.com/feed` — таймаут TLS. Рабочий адрес — `/rss`.
- `rap.ru/rss.xml` — 200, но это не лента с записями. `rap.ru/feed` — 404.
- `nplus1.ru/rss/astronomy` и `/rss/news` — 403.
- `habr` hubs neuroscience, psychology, music, sci-fi, anime — 404. Хаб «читальный зал» живой, но это длинные тексты, не лента релизов, и он не подключён.
- `anti-malware.ru/rss.xml` — 200, но свежая запись в пробе от 21 марта 2019. Не подключён.
- `hi-tech.mail.ru/rss/`, `mobile-review.com/news/rss.xml`, `gamemag.ru/rss.xml` — 404.
- `psychologies.ru/feed` — 404.
- Государственные информагентства в список не ставились: правило отбора не берёт пропагандистские ленты ни одной стороны. Это решение о составе списка, а не автоматический разбор тона каждой заметки. ТАСС при этом технически отвечал 200 на `https://tass.ru/rss/v2.xml` и в сборщик не входит.
