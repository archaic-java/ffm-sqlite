module work.archaic.sqlite.test {
    requires work.archaic.service.catalog;
    requires work.archaic.service.catalog.test;
    uses work.archaic.service.sqlite.v01.Sqlite;
    exports work.archaic.sqlite.test to work.archaic.minau;
}
