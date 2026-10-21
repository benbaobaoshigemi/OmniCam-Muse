// Runs the actual portable device baker for every selectable look; samples are checked in Python.
#include "../../app/src/main/cpp/master_core.h"
#include <cmath>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
int main(int argc, char **argv) {
    if (argc != 5) return 2;
    std::string error;
    if (!mastercolor::loadEngine(argv[1], &error)) { std::fprintf(stderr, "%s\n", error.c_str()); return 1; }
    mastercolor::Key key;
    if (!mastercolor::readKeyFile(argv[4], key)) return 1;
    if (key.path != mastercolor::Path::Preview || key.cct != 5000 || key.density != std::array<float,3>{0.5f,1,1.5f}) return 1;
    key.density = {1,1,1};
    std::ifstream names(argv[2]);
    std::string line;
    const int axis[] = {0,8,24,40,63};
    int count = 0;
    while (std::getline(names, line)) {
        if (line.empty()) continue;
        // "<look> [p0 p1 p2 p3]": the look and, optionally, its parameters (nan = default); output stem joins the tokens by '_'.
        std::istringstream tokens(line);
        std::string stem, token;
        tokens >> key.look;
        stem = key.look;
        key.param = {NAN, NAN, NAN, NAN};
        for (int i = 0; i < 4 && (tokens >> token); ++i) {
            key.param[i] = std::strtof(token.c_str(), nullptr);
            stem += "_" + token;
        }
        key.path = mastercolor::Path::Main;
        key.luxBlend = false;
        std::vector<std::vector<float>> tables;
        if (!mastercolor::lookTables(key, tables, 8)) { std::fprintf(stderr, "bake failed: %s\n", line.c_str()); return 1; }
        for (const auto &t : tables) for (float v : t) if (!std::isfinite(v) || v < 0 || v > 1) return 1;
        const std::vector<float> &lut = tables[0];
        std::ofstream out(std::string(argv[3]) + "/" + stem + ".f32", std::ios::binary);
        for (int b : axis) for (int g : axis) for (int r : axis) for (int c = 0; c < 3; ++c) {
            float v = lut[size_t(c)*64*64*64 + (b*64+g)*64+r];
            out.write(reinterpret_cast<const char *>(&v), sizeof(v));
        }
        if (!out) return 1;
        if (++count % 20 == 0) std::printf("baked %d looks\n", count);
    }
    // Parameter changes must invalidate the cache and affect the selected negative's output.
    key.look = "film_kodak_portra_400";
    std::string normal = key.name();
    std::vector<float> a, b;
    if (!mastercolor::bake(key, a, 8)) return 1;
    key.density = {0.5f,1,1.5f};
    if (normal == key.name() || !mastercolor::bake(key, b, 8) || a == b) return 1;
    // The basic Gamma exponent is part of the key and changes the table; other basic looks ignore it.
    key.density = {1,1,1};
    key.param = {NAN, NAN, NAN, NAN};
    for (const char *look : {"basic_gamma", "sigmoid", "agx", "filmic"}) {
        // A look parameter is part of the key and changes the table; an unset parameter is the file's default.
        key.look = look;
        std::string defaults = key.name();
        std::vector<float> d, e, f;
        if (!mastercolor::bake(key, d, 8)) return 1;
        key.param = {key.look == "filmic" ? -6.0f : key.look == "agx" ? -8.0f : key.look == "sigmoid" ? 2.2f : 2.2f, NAN, NAN, NAN};
        if (defaults == key.name() || !mastercolor::bake(key, e, 8) || d == e) return 1;
        key.param = {NAN, NAN, NAN, NAN};
        if (defaults != key.name() || !mastercolor::bake(key, f, 8) || d != f) return 1;
    }
    key.look = "basic_rec709";
    std::string rec = key.name();
    key.param = {2.2f, NAN, NAN, NAN};
    if (rec == key.name()) return 1;  // the name hashes whatever the key carries; the Java side only sends parameters of the active look
    std::printf("PASS %d looks, MCOLKEY8, density cache, look parameters and rendering\n", count);
    return 0;
}
