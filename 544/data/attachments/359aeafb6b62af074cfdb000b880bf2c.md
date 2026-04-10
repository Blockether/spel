## GET http://localhost:36977/echo?name=Alice&role=Engineer&team=Platform → 200 OK

### Request Headers
```
GET http://localhost:36977/echo?name=Alice&role=Engineer&team=Platform
```

### Response Headers
```
content-length: 80
content-type: application/json
date: Fri, 10 Apr 2026 08:22:07 GMT
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
curl 'http://localhost:36977/echo?name=Alice&role=Engineer&team=Platform'
```
