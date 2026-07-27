## DELETE http://localhost:39283/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 27 Jul 2026 13:34:39 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:39283/echo' \
  -X DELETE
```
