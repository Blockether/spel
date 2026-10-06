## DELETE http://localhost:35623/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 05 Oct 2026 23:18:43 GMT
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
curl 'http://localhost:35623/echo' \
  -X DELETE
```
