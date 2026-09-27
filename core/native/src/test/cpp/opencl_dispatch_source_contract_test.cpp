#include <cassert>
#include <fstream>
#include <sstream>
#include <string>

namespace {

std::string readFile(const char * path) {
    std::ifstream input(path, std::ios::binary);
    std::ostringstream contents;
    contents << input.rdbuf();
    return contents.str();
}

void requireContains(const std::string & source, const char * needle) {
    assert(source.find(needle) != std::string::npos);
}

} // namespace

int main(int argc, char ** argv) {
    assert(argc == 2);
    const std::string source = readFile(argv[1]);
    assert(!source.empty());

    requireContains(source, "dlopen");
    requireContains(source, "dlsym");
    requireContains(source, "clGetPlatformIDs");
    requireContains(source, "clGetKernelInfo");
    requireContains(source, "clGetEventProfilingInfo");
    requireContains(source, "CL_INVALID_OPERATION");
    assert(source.find("DT_NEEDED") == std::string::npos);
    assert(source.find("RTLD_GLOBAL") == std::string::npos);
    return 0;
}
