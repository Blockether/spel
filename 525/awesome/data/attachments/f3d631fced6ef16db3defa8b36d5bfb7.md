## GET http://localhost:34011/echo?name=Alice&role=Engineer&team=Platform → 200 OK

### Request Headers
```
GET http://localhost:34011/echo?name=Alice&role=Engineer&team=Platform
```

### Response Headers
```
content-length: 80
content-type: application/json
date: Thu, 02 Apr 2026 09:59:54 GMT
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
curl 'http://localhost:34011/echo?name=Alice&role=Engineer&team=Platform'
```
