## PUT http://localhost:42133/echo → 200 OK

### Request Headers
```
PUT http://localhost:42133/echo
```

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
date: Mon, 02 Mar 2026 12:00:02 GMT
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
curl 'http://localhost:42133/echo' \
  -X PUT \
  -d '{"name":"Eve Updated"}'
```
