## PATCH http://localhost:34751/echo → 200 OK

### Request Body
```json
{
  "email": "alice3@example.org"
}
```

### Response Headers
```
content-length: 71
content-type: application/json
date: Fri, 11 Sep 2026 03:35:49 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "alice3@example.org"
  }
}
```

### cURL
```bash
curl 'http://localhost:34751/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
