[out:json][timeout:180];
area["ISO3166-1"="KR"][admin_level=2]->.kr;
node["name"~"인증센터"](area.kr);
out;
