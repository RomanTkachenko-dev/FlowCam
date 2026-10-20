# FlowCam

**FlowCam допомагає побачити невидиме — через його вплив на світло.**

Це експериментальний застосунок для Android, створений для спостереження за оптичними змінами, які може спричиняти нагріте повітря. Він використовує камеру смартфона, без додавання диму чи частинок у потік.

Неоднорідне нагрівання повітря створює відмінності його густини та показника заломлення. Проходячи крізь такі ділянки, світло змінює напрямок, а зображення фону трохи спотворюється. Схожий ефект можна побачити над гарячим асфальтом; слабші зміни часто залишаються непомітними для ока.

FlowCam порівнює кадри нерухомого деталізованого фону, оцінює шум і підсилює невеликі зміни зображення. Результат накладається на відео як кольорова карта; експериментальний режим також показує локальні зміщення деталей фону. Досліди можна записувати та повторно аналізувати на телефоні.

Застосунок створений для наочних експериментів із тепловими струменями — наприклад, біля фена чи системи охолодження. Він показує оптичний вплив середовища, а не безпосередньо рух молекул: стрілки позначають деформацію зображення, не швидкість повітря. Для спостереження потрібні відповідний фон, стабільне освітлення та достатні оптичні неоднорідності.

## English

**FlowCam helps reveal the invisible through its effect on light.**

FlowCam is an experimental Android app for observing optical changes that heated air can produce. It uses a smartphone camera, without adding smoke or tracer particles to the flow.

Uneven heating creates variations in air density and refractive index. Light passing through these regions changes direction, slightly distorting the background image. The familiar shimmer above hot pavement is an example; weaker changes can be difficult to notice with the naked eye.

FlowCam compares images of a stationary, detailed background, estimates noise, and amplifies small image changes. It overlays a colour map on the video; an experimental mode also displays local background-image displacements. Experiments can be recorded and reprocessed on the phone.

The app was created for hands-on experiments with thermal plumes, such as those near a hair dryer or cooling system. It visualises optical effects rather than directly tracking air molecules: arrows represent image deformation, not air velocity. Observation requires a suitable background, stable lighting, and sufficient optical inhomogeneity.

## Інструкція / User guide

- [Українською: перший дослід, інтерфейс і результати](docs/USER_GUIDE.uk.md)
- [English: first experiment, interface and results](docs/USER_GUIDE.en.md)
- [Технічні деталі / Development notes (Ukrainian)](docs/DEVELOPMENT.md)

### Інтерфейс / Interface

Схема за поточним компонуванням застосунку, не знімок екрана. / Diagram based on the current app layout, not a screenshot.

![FlowCam interface diagram](docs/images/interface.svg)

### Як читати результат / Reading the output

Умовний приклад, не експериментальні дані й не прогноз чутливості. / Schematic illustration, not experimental data or a sensitivity prediction.

![Illustrative optical-change and deformation views](docs/images/results.svg)
