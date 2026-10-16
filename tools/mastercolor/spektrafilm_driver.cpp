// Host asset generator using PhotonCamera spectral implementation unchanged.
// argv: model_dir film out_dir [paper ...]; positives have no paper arguments.
#include "spectral_generator.h"
#include <cstdio>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>
using namespace spektrafilm;
static std::vector<uint8_t> read(const std::string &path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::runtime_error("Cannot read " + path);
    return {std::istreambuf_iterator<char>(f), {}};
}
static void write(const std::string &path, const std::vector<float> &values) {
    std::ofstream f(path, std::ios::binary);
    f.write(reinterpret_cast<const char *>(values.data()), values.size() * sizeof(float));
    if (!f) throw std::runtime_error("Cannot write " + path);
}
int main(int argc, char **argv) {
    if (argc < 4) return 2;
    try {
        const std::string dir = argv[1], stock = argv[2], out = argv[3];
        auto sharedBytes = read(dir + "/shared.bin"), filmBytes = read(dir + "/" + stock + ".bin");
        DataSet shared = readDataSet(sharedBytes.data(), sharedBytes.size());
        DataSet model = readDataSet(filmBytes.data(), filmBytes.size());
        Filming film(shared, model);
        DensityWire wire = measureDensityWire(film);
        const OutputGamut gamut;
        if (film.positive()) {
            if (argc != 4) throw std::runtime_error("Positive film does not use print paper");
            Scanner scanner(shared, model, gamut);
            write(out + "/" + stock + "_film.f32", generateFilmLut(film, wire, &scanner, 65));
        } else {
            if (argc == 4) throw std::runtime_error("Negative film requires print paper");
            write(out + "/" + stock + "_film.f32", generateFilmLut(film, wire, nullptr, 65));
            for (int i = 4; i < argc; ++i) {
                std::string paper = argv[i];
                auto paperBytes = read(dir + "/" + paper + ".bin");
                DataSet paperModel = readDataSet(paperBytes.data(), paperBytes.size());
                Printing printing(shared, model, paperModel, paper, film.midgrayDensity());
                Scanner scanner(shared, paperModel, gamut);
                write(out + "/" + stock + "__" + paper + "_print.f32", generatePrintLut(printing, scanner, wire, 65));
            }
        }
        std::printf("generated %s (%d prints)\n", stock.c_str(), argc - 4);
        return 0;
    } catch (const std::exception &error) {
        std::fprintf(stderr, "%s\n", error.what());
        return 1;
    }
}
