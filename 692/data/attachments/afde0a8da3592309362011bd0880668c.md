## GET http://localhost:45521/echo?name=Alice&role=Engineer&team=Platform → 200 OK

### Response Headers
```
content-length: 80
content-type: application/json
date: Sun, 09 Aug 2026 20:00:50 GMT
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
curl 'http://localhost:45521/echo?name=Alice&role=Engineer&team=Platform'
```
