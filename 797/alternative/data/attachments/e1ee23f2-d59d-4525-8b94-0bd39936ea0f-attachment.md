## GET http://localhost:33111/echo?name=Alice&role=Engineer&team=Platform → 200 OK

### Response Headers
```
content-length: 80
content-type: application/json
date: Sat, 24 Oct 2026 08:08:32 GMT
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
curl 'http://localhost:33111/echo?name=Alice&role=Engineer&team=Platform'
```
