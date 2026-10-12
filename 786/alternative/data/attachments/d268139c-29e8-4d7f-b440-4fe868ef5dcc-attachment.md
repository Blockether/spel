## GET http://localhost:37099/echo?name=Alice&role=Engineer&team=Platform → 200 OK

### Response Headers
```
content-length: 80
content-type: application/json
date: Mon, 12 Oct 2026 16:15:43 GMT
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
curl 'http://localhost:37099/echo?name=Alice&role=Engineer&team=Platform'
```
