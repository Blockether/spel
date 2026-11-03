## GET http://localhost:39639/echo?name=Alice&role=Engineer&team=Platform → 200 OK

### Response Headers
```
content-length: 80
content-type: application/json
date: Tue, 03 Nov 2026 17:51:39 GMT
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
curl 'http://localhost:39639/echo?name=Alice&role=Engineer&team=Platform'
```
