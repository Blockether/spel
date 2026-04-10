## GET http://localhost:41257/echo?name=Alice&role=Engineer&team=Platform → 200 OK

### Request Headers
```
GET http://localhost:41257/echo?name=Alice&role=Engineer&team=Platform
```

### Response Headers
```
content-length: 80
content-type: application/json
date: Fri, 10 Apr 2026 22:29:04 GMT
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
curl 'http://localhost:41257/echo?name=Alice&role=Engineer&team=Platform'
```
