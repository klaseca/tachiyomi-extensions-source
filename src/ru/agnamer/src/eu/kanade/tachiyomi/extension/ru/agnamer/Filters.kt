package eu.kanade.tachiyomi.extension.ru.agnamer

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

class CheckFilter(name: String, val id: String) : Filter.CheckBox(name)

class SearchFilter(name: String, val id: String) : Filter.TriState(name)

class OrderBy : Filter.Sort(
    "Сортировка",
    arrayOf(
        "Новизне",
        "Последним обновлениям",
        "Популярности",
        "Лайкам",
        "Просмотрам",
        "По кол-ву глав",
        "Мне повезет",
    ),
    Selection(2, false),
)

class RequireChapters : Filter.Select<String>(
    "Только проекты с главами",
    arrayOf("Да", "Все"),
)

class RequireEX : Filter.Select<String>(
    "Использовать поиск",
    arrayOf("Remanga", "ExManga(без фильтров)"),
)

private val ageList = listOf(
    CheckFilter("Для всех", "0"),
    CheckFilter("16+", "1"),
    CheckFilter("18+", "2"),
)

class Age : Filter.Group<CheckFilter>("Возрастное ограничение", ageList)

private val statusList = listOf(
    CheckFilter("Закончен", "0"),
    CheckFilter("Продолжается", "1"),
    CheckFilter("Заморожен", "2"),
    CheckFilter("Нет переводчика", "3"),
    CheckFilter("Анонс", "4"),
    CheckFilter("Лицензировано", "5"),
)

class Status : Filter.Group<CheckFilter>("Статус", statusList)

private val typeList = listOf(
    SearchFilter("Манга", "0"),
    SearchFilter("Манхва", "1"),
    SearchFilter("Маньхуа", "2"),
    SearchFilter("Западный комикс", "3"),
    SearchFilter("Рукомикс", "4"),
    SearchFilter("Индонезийский комикс", "5"),
    SearchFilter("Другое", "6"),
)

class Type : Filter.Group<SearchFilter>("Типы", typeList)

private val categoryList = listOf(
    SearchFilter("веб", "5"),
    SearchFilter("в цвете", "6"),
    SearchFilter("ёнкома", "8"),
    SearchFilter("сборник", "10"),
    SearchFilter("сингл", "11"),
    SearchFilter("алхимия", "47"),
    SearchFilter("ангелы", "48"),
    SearchFilter("антигерой", "26"),
    SearchFilter("антиутопия", "49"),
    SearchFilter("апокалипсис", "50"),
    SearchFilter("аристократия", "117"),
    SearchFilter("армия", "51"),
    SearchFilter("артефакты", "52"),
    SearchFilter("амнезия / потеря памяти", "123"),
    SearchFilter("боги", "45"),
    SearchFilter("борьба за власть", "52"),
    SearchFilter("будущее", "55"),
    SearchFilter("бои на мечах", "122"),
    SearchFilter("вампиры", "112"),
    SearchFilter("вестерн", "56"),
    SearchFilter("видеоигры", "35"),
    SearchFilter("виртуальная реальность", "44"),
    SearchFilter("владыка демонов", "57"),
    SearchFilter("военные", "29"),
    SearchFilter("волшебные существа", "59"),
    SearchFilter("воспоминания из другого мира", "60"),
    SearchFilter("врачи / доктора", "116"),
    SearchFilter("выживание", "41"),
    SearchFilter("горничные", "23"),
    SearchFilter("гяру", "28"),
    SearchFilter("гг женщина", "63"),
    SearchFilter("гг мужчина", "64"),
    SearchFilter("умный гг", "111"),
    SearchFilter("тупой гг", "109"),
    SearchFilter("гг имба", "110"),
    SearchFilter("гг не человек", "123"),
    SearchFilter("грузовик-сан", "125"),
    SearchFilter("геймеры", "61"),
    SearchFilter("гильдии", "62"),
    SearchFilter("гоблины", "65"),
    SearchFilter("девушки-монстры", "37"),
    SearchFilter("демоны", "15"),
    SearchFilter("драконы", "66"),
    SearchFilter("дружба", "67"),
    SearchFilter("жестокий мир", "69"),
    SearchFilter("животные компаньоны", "70"),
    SearchFilter("завоевание мира", "71"),
    SearchFilter("зверолюди", "19"),
    SearchFilter("зомби", "14"),
    SearchFilter("игровые элементы", "73"),
    SearchFilter("исекай", "115"),
    SearchFilter("квесты", "75"),
    SearchFilter("космос", "76"),
    SearchFilter("кулинария", "16"),
    SearchFilter("культивация", "18"),
    SearchFilter("лоли", "108"),
    SearchFilter("магическая академия", "78"),
    SearchFilter("магия", "22"),
    SearchFilter("мафия", "24"),
    SearchFilter("медицина", "17"),
    SearchFilter("месть", "79"),
    SearchFilter("монстры", "38"),
    SearchFilter("музыка", "39"),
    SearchFilter("навыки / способности", "80"),
    SearchFilter("наёмники", "81"),
    SearchFilter("насилие / жестокость", "82"),
    SearchFilter("нежить", "83"),
    SearchFilter("ниндзя", "30"),
    SearchFilter("офисные работники", "40"),
    SearchFilter("обратный гарем", "40"),
    SearchFilter("оборотни", "113"),
    SearchFilter("пародия", "85"),
    SearchFilter("подземелья", "86"),
    SearchFilter("политика", "87"),
    SearchFilter("полиция", "32"),
    SearchFilter("преступники / криминал", "36"),
    SearchFilter("призраки / духи", "27"),
    SearchFilter("прокачка", "118"),
    SearchFilter("путешествия во времени", "43"),
    SearchFilter("разумные расы", "88"),
    SearchFilter("ранги силы", "68"),
    SearchFilter("реинкарнация", "13"),
    SearchFilter("роботы", "89"),
    SearchFilter("рыцари", "90"),
    SearchFilter("средневековье", "25"),
    SearchFilter("самураи", "33"),
    SearchFilter("система", "91"),
    SearchFilter("скрытие личности", "93"),
    SearchFilter("спасение мира", "94"),
    SearchFilter("стимпанк", "92"),
    SearchFilter("супергерои", "95"),
    SearchFilter("традиционные игры", "34"),
    SearchFilter("учитель / ученик", "96"),
    SearchFilter("управление территорией", "114"),
    SearchFilter("философия", "97"),
    SearchFilter("хентай", "12"),
    SearchFilter("хикикомори", "21"),
    SearchFilter("шантаж", "99"),
    SearchFilter("эльфы", "46"),
)

class Category : Filter.Group<SearchFilter>("Категории", categoryList)

private val genreList = listOf(
    SearchFilter("боевые искусства", "3"),
    SearchFilter("гарем", "5"),
    SearchFilter("гендерная интрига", "6"),
    SearchFilter("героическое фэнтези", "7"),
    SearchFilter("детектив", "8"),
    SearchFilter("дзёсэй", "9"),
    SearchFilter("додзинси", "10"),
    SearchFilter("драма", "11"),
    SearchFilter("история", "13"),
    SearchFilter("киберпанк", "14"),
    SearchFilter("кодомо", "15"),
    SearchFilter("комедия", "50"),
    SearchFilter("махо-сёдзё", "17"),
    SearchFilter("меха", "18"),
    SearchFilter("мистика", "19"),
    SearchFilter("мурим", "51"),
    SearchFilter("научная фантастика", "20"),
    SearchFilter("повседневность", "21"),
    SearchFilter("постапокалиптика", "22"),
    SearchFilter("приключения", "23"),
    SearchFilter("психология", "24"),
    SearchFilter("психодел-упоротость-треш", "124"),
    SearchFilter("романтика", "25"),
    SearchFilter("сверхъестественное", "27"),
    SearchFilter("сёдзё", "28"),
    SearchFilter("сёдзё-ай", "29"),
    SearchFilter("сёнэн", "30"),
    SearchFilter("сёнэн-ай", "31"),
    SearchFilter("спорт", "32"),
    SearchFilter("сэйнэн", "33"),
    SearchFilter("трагедия", "34"),
    SearchFilter("триллер", "35"),
    SearchFilter("ужасы", "36"),
    SearchFilter("фантастика", "37"),
    SearchFilter("фэнтези", "38"),
    SearchFilter("школьная жизнь", "39"),
    SearchFilter("экшен", "2"),
    SearchFilter("элементы юмора", "16"),
    SearchFilter("эротика", "42"),
    SearchFilter("этти", "40"),
    SearchFilter("юри", "41"),
)

class Genre : Filter.Group<SearchFilter>("Жанры", genreList)

data class MyListUnit(val name: String, val id: String)

val myList = listOf(
    MyListUnit("Каталог", "-"),
    MyListUnit("Все закладки", "all"),
    MyListUnit("Читаю", "1"),
    MyListUnit("Буду читать", "2"),
    MyListUnit("Прочитано", "3"),
    MyListUnit("Брошено ", "4"),
    MyListUnit("Отложено", "5"),
    MyListUnit("Не интересно ", "6"),
)

private val myStatus = myList.map {
    it.name
}.toTypedArray()

class My : Filter.Select<String>("Закладки (только)", myStatus)

val filterList = FilterList(
    RequireEX(),
    OrderBy(),
    Genre(),
    Category(),
    Type(),
    Status(),
    Age(),
    My(),
    RequireChapters(),
)
