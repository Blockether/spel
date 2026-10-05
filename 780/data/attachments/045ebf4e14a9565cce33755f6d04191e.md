## DELETE http://localhost:36563/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 05 Oct 2026 07:50:51 GMT
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
curl 'http://localhost:36563/echo' \
  -X DELETE
```
