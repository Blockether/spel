## GET http://localhost:38941/echo?name=Alice&role=Engineer&team=Platform → 200 OK

### Response Headers
```
content-length: 80
content-type: application/json
date: Sat, 21 Nov 2026 19:57:30 GMT
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
curl 'http://localhost:38941/echo?name=Alice&role=Engineer&team=Platform'
```
