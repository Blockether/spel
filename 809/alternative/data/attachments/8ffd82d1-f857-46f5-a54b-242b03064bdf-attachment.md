## PATCH http://localhost:36473/echo → 200 OK

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
date: Fri, 30 Oct 2026 16:41:27 GMT
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
curl 'http://localhost:36473/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
