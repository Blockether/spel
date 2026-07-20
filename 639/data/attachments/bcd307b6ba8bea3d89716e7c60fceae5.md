## PUT http://localhost:45439/echo → 200 OK

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Mon, 20 Jul 2026 10:37:14 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:45439/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
