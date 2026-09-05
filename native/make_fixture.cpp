// make_fixture.cpp -- writes a small, real .mdsi file for end-to-end testing
// of the JNI layer, using the actual madras::dv1::builder API.
#include <cstdio>
#include <cstring>
#include <vector>
#include "madras/dv1/common.hpp"
#include "madras/dv1/builder/madras_builder.hpp"

using namespace madras::dv1;

int main() {
    // 3 columns: id (INT, PK), name (TEXT, trie-indexed), score (DOUBLE)
    const char *names = "fixture,id,name,score";
    char col_types[4]    = { MST_INT, MST_TEXT, MST_DECV, '\0' };
    char col_encodings[4] = { 't', 'T', 'v', '\0' }; // id: trie (PK), name: trie-2way (col_trie_map lookup), score: plain

    builder bldr("/home/claude/build/fixture.mdsi", names, 3,
                 col_types, col_encodings, "000", 0, /*pk_col_count=*/1);

    auto *col_tbl_bldr = bldr.get_col_table_bldr();

    int32_t ids[5]     = {1, 2, 3, 4, 5};
    double scores[5]   = {1.5, 2.5, 3.5, 4.5, 5.5};
    const char *names_arr[5] = {"Alice", "Bob", "Carol", "Dave", "John"};

    uint64_t valid_all = ~0ULL;

    col_tbl_bldr->append_vector(0, ids, &valid_all, 5);

    // db_string_t construction, per data_ingestion.hpp's usage pattern
    std::vector<db_string_t> name_views;
    for (int i = 0; i < 5; i++) name_views.emplace_back(names_arr[i], (uint32_t) strlen(names_arr[i]));
    col_tbl_bldr->append_vector(1, name_views.data(), &valid_all, 5);

    col_tbl_bldr->append_vector(2, scores, &valid_all, 5);

    // Index PK rows (mirrors madras_duckdb_copyto.cpp's Sink logic for pk_col_count > 0)
    uintxx_t rec_count = col_tbl_bldr->get_record_count();
    std::vector<col_value> col_vals(3);
    std::vector<uint8_t> valids(3);
    for (uintxx_t row = 0; row < rec_count; row++) {
        for (size_t c = 0; c < 3; c++) {
            column_storage *col = col_tbl_bldr->get_data(c);
            col_vals[c] = col->get_col_value(row);
            valids[c] = (*col->get_null_bv())[row] ? 0 : 1;
        }
        bldr.insert_record(col_vals.data(), valids.data(), row, false);
    }

    bldr.build_and_write_all();
    printf("Fixture written: /home/claude/build/fixture.mdsi (%llu rows)\n", (unsigned long long) rec_count);
    return 0;
}
