module work.archaic.sqlite {
    requires work.archaic.service.catalog;
    provides work.archaic.service.sqlite.v01.Sqlite with work.archaic.sqlite.FfmSqlite;
}
