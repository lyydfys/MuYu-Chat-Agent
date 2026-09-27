#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <map>
#include <string>
#include <vector>
#include "MNN_generated.h"

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    std::ifstream f(argv[1], std::ios::binary);
    std::vector<unsigned char> b((std::istreambuf_iterator<char>(f)), {});
    if (b.empty()) return 3;
    auto* net = MNN::GetNet(b.data());
    if (!net || !net->oplists()) return 4;
    std::map<int, int> counts;
    for (unsigned i=0; i<net->oplists()->size(); ++i) {
        auto* op = net->oplists()->Get(i);
        if (!op) continue;
        ++counts[(int)op->type()];
    }
    for (auto& p: counts) std::cout << p.first << " " << p.second << "\n";
    std::cout << "ops=" << net->oplists()->size() << "\n";
    return 0;
}
