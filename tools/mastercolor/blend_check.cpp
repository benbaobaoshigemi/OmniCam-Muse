// Host check for the blend mode (master_core.cpp): bakes a look, blends it with the stock LMT at a few
// weights and writes the tables for tools/mastercolor/blend_check.py.
//   blend_check <hncs dir with hncs_engine.bin + looks/> <stock lmt dir> <out dir> <MCOLKEY8> [look]
#include <cstdio>
#include <string>
#include <vector>

#include "../../app/src/main/cpp/master_core.h"

static void save(const std::string &path, const std::vector<float> &t) {
    FILE *f = std::fopen(path.c_str(), "wb");
    std::fwrite(t.data(), 4, t.size(), f);
    std::fclose(f);
}

int main(int argc, char **argv) {
    if (argc < 5) return 2;
    std::string error;
    if (!mastercolor::loadEngine(std::string(argv[1]) + "/hncs_engine.bin", &error)) {
        std::fprintf(stderr, "engine: %s\n", error.c_str());
        return 1;
    }
    mastercolor::Key key;
    if (!mastercolor::readKeyFile(argv[4], key)) return 1;
    key.path = mastercolor::Path::Main;
    key.cct = 5000;
    key.look = argc > 5 ? argv[5] : "hncs";
    std::vector<float> look;
    if (!mastercolor::bake(key, look, 4)) return 1;
    save(std::string(argv[3]) + "/look.f32", look);
    const float weights[][2] = {{1, 1}, {0, 0}, {1, 0}, {0, 1}, {0.5f, 0.5f}, {0.25f, 0.5f}, {0.75f, 0.5f}, {0.5f, 0.25f}};
    for (auto &w : weights) {
        key.toneWeight = w[0];
        key.colourWeight = w[1];
        std::vector<std::vector<float>> tables;
        if (!mastercolor::blendWithStock(key, {look}, argv[2], tables, 4)) {
            std::fprintf(stderr, "blend %.2f/%.2f failed\n", w[0], w[1]);
            return 1;
        }
        for (size_t i = 0; i < tables.size(); i++)
            save(std::string(argv[3]) + "/blend_" + std::to_string(int(w[0] * 100)) + "_" + std::to_string(int(w[1] * 100)) + "_" + std::to_string(i) + ".f32", tables[i]);
        std::printf("blend %.2f/%.2f: %zu tables\n", w[0], w[1], tables.size());
    }
    return 0;
}
