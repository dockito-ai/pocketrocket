// Counts words on stdin and prints the three most common: string, map, ranges, exceptions.
#include <algorithm>
#include <iostream>
#include <map>
#include <ranges>
#include <stdexcept>
#include <string>
#include <vector>

int main() {
  std::map<std::string, int> counts;
  for (std::string w; std::cin >> w;) ++counts[w];
  std::vector<std::pair<std::string, int>> v(counts.begin(), counts.end());
  std::ranges::sort(v, [](auto& a, auto& b) { return a.second > b.second; });
  for (auto& [w, n] : v | std::views::take(3)) std::cout << w << ' ' << n << '\n';
  try { throw std::runtime_error("exceptions work"); } catch (const std::exception& e) { std::cout << e.what() << '\n'; }
}
