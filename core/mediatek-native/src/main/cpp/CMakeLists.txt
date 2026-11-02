cmake_minimum_required(VERSION 3.22.1)

project(mca_mediatek_neuron CXX)

add_library(mca_mediatek_neuron SHARED mediatek_neuron_adapter.cpp)

target_compile_features(mca_mediatek_neuron PRIVATE cxx_std_17)

# The vendor Neuron runtime is resolved with dlopen at execution time; nothing
# vendor-owned is linked into the APK.
target_link_libraries(mca_mediatek_neuron PRIVATE log dl)
