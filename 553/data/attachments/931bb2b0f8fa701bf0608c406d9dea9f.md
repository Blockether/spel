## GET http://localhost:34041/echo?name=Alice&role=Engineer&team=Platform → 200 OK

### Request Headers
```
GET http://localhost:34041/echo?name=Alice&role=Engineer&team=Platform
```

### Response Headers
```
content-length: 80
content-type: application/json
date: Sat, 11 Apr 2026 09:37:14 GMT
```

### Response Body
```json
{
  "method": "GET",
  "path": "/echo",
  "query": "name=Alice&role=Engineer&team=Platform"
}
```

### cURL
```bash
curl 'http://localhost:34041/echo?name=Alice&role=Engineer&team=Platform'
```
